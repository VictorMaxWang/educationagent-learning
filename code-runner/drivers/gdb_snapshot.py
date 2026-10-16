"""由 GDB 内嵌 Python 加载；仅读取调试信息和内存，不执行学生表达式。"""
import json
import gdb


# GDB 的 DWARF 作用域可能覆盖到函数收尾，此时栈上 vector 对象仍可见而堆已释放。
# 只记录真实构造/析构停点的对象地址，不通过“看起来合理”的数据猜测生命周期。
DESTROYED = set()


class TrackLifetime(gdb.Command):
    def __init__(self):
        super().__init__("xtrace-track-lifetime", gdb.COMMAND_USER)

    def invoke(self, argument, from_tty):
        try:
            frame = gdb.newest_frame()
            name = (frame.name() or "").rsplit("::", 1)[-1].split("(", 1)[0].split("<", 1)[0]
            if name not in ("vector", "basic_string", "~vector", "~basic_string"):
                return
            address = hex(int(frame.read_var("this")))
            if name.startswith("~"):
                DESTROYED.add(address)
            else:
                # 循环内对象可能复用同一个栈地址，观察到新构造后才允许重新读取。
                DESTROYED.discard(address)
        except BaseException:
            # 标准库缺少 this 的调试信息时不推断；后续作用域/声明行检查仍保守降级。
            pass


class Snapshot(gdb.Command):
    def __init__(self):
        super().__init__("xtrace-snapshot", gdb.COMMAND_USER)

    def invoke(self, argument, from_tty):
        heap, seen, frames = {}, set(), []
        lifetime_ended = False

        def serialize(value, depth=0):
            try:
                if value.is_optimized_out:
                    return {"$unavailable": "值已被编译器优化"}
                kind = value.type.strip_typedefs().code
                typename = str(value.type.strip_typedefs())
                address = hex(int(value.address)) if value.address else None
                if address in DESTROYED:
                    return {"$unavailable": "对象生命周期已结束"}
                if kind == gdb.TYPE_CODE_BOOL:
                    return bool(value)
                if kind in (gdb.TYPE_CODE_INT, gdb.TYPE_CODE_ENUM):
                    return int(value)
                if kind == gdb.TYPE_CODE_FLT:
                    number = float(value)
                    return number if number == number and abs(number) != float("inf") else {"$unavailable": str(number)}
                if kind in (gdb.TYPE_CODE_REF, gdb.TYPE_CODE_RVALUE_REF):
                    return serialize(value.referenced_value(), depth)
                if kind == gdb.TYPE_CODE_PTR:
                    if int(value) == 0:
                        return None
                    identity = hex(int(value))
                    if identity not in seen:
                        seen.add(identity)
                        heap[identity] = {"type": str(value.type.target()), "value": serialize(value.dereference(), depth + 1)} if depth < 3 and len(heap) < 128 else {"$unavailable": "最大展开深度或对象数量"}
                    return {"$ref": identity}
                if "basic_string<char" in typename or typename in ("std::string", "std::__cxx11::string"):
                    length = int(value["_M_string_length"])
                    pointer = value["_M_dataplus"]["_M_p"]
                    if length < 0 or length > 10000000 or (length > 0 and int(pointer) == 0):
                        return {"$unavailable": "字符串状态不可确认"}
                    return pointer.string(length=min(length, 500), errors="replace")
                identity = str(value.address) if value.address else "value:" + str(len(heap))
                if identity in seen:
                    return {"$ref": identity}
                seen.add(identity)
                if depth >= 3 or len(heap) >= 128:
                    heap[identity] = {"type": typename, "$unavailable": "最大展开深度" if depth >= 3 else "最大对象数量"}
                elif kind == gdb.TYPE_CODE_ARRAY:
                    lower, upper = value.type.range()
                    length = upper - lower + 1
                    heap[identity] = {"type": typename, "length": length, "items": [serialize(value[i], depth + 1) for i in range(lower, min(upper + 1, lower + 50))], "truncated": length > 50}
                elif "std::vector<" in typename:
                    begin, end = value["_M_impl"]["_M_start"], value["_M_impl"]["_M_finish"]
                    capacity = value["_M_impl"]["_M_end_of_storage"]
                    addresses = [int(begin), int(end), int(capacity)]
                    if not all(pointer == 0 for pointer in addresses) and (any(pointer == 0 for pointer in addresses) or not addresses[0] <= addresses[1] <= addresses[2]):
                        return {"$unavailable": "数组对象状态不可确认"}
                    length = int(end - begin) if int(begin) else 0
                    if length < 0 or length > 10000000:
                        return {"$unavailable": "数组对象长度不可确认"}
                    heap[identity] = {"type": typename, "length": length, "items": [serialize((begin + i).dereference(), depth + 1) for i in range(min(length, 50))], "truncated": length > 50}
                elif kind in (gdb.TYPE_CODE_STRUCT, gdb.TYPE_CODE_UNION):
                    fields = {}
                    for field in value.type.fields()[:40]:
                        if field.name and not field.is_base_class:
                            fields[field.name] = serialize(value[field.name], depth + 1)
                    heap[identity] = {"type": typename, "properties": fields}
                else:
                    heap[identity] = {"type": typename, "$unavailable": "该类型暂不能展开"}
                return {"$ref": identity}
            except BaseException as exc:
                return {"$unavailable": str(exc)[:120]}

        frame = gdb.newest_frame()
        line = 0
        while frame and len(frames) < 20:
            try:
                location = frame.find_sal()
                if location.symtab and location.symtab.filename.endswith("main.cpp"):
                    if not line:
                        line = location.line
                    locals_map, block = {}, frame.block()
                    while block:
                        for symbol in block:
                            if (symbol.is_argument or symbol.is_variable) and symbol.name and symbol.name not in locals_map:
                                declaration_line = getattr(symbol, "line", 0) or 0
                                # 行级调试停在该行执行前。禁止提前读取尚未构造的局部对象；
                                # 同行循环声明保守显示未知，循环体的后续行仍可读取真实变量。
                                if not symbol.is_argument and declaration_line >= location.line:
                                    locals_map[symbol.name] = {"$unavailable": "尚未执行变量声明"}
                                elif not symbol.is_argument and declaration_line <= 0:
                                    locals_map[symbol.name] = {"$unavailable": "变量声明位置未知"}
                                else:
                                    value = frame.read_var(symbol)
                                    address = hex(int(value.address)) if value.address else None
                                    if address in DESTROYED:
                                        lifetime_ended = True
                                        locals_map[symbol.name] = {"$unavailable": "对象生命周期已结束"}
                                    else:
                                        locals_map[symbol.name] = serialize(value)
                        if block.function:
                            break
                        block = block.superblock
                    frames.append({"name": frame.name() or "<main>", "locals": locals_map})
            except BaseException:
                pass
            frame = frame.older()
        with open(argument.strip(), "w", encoding="utf-8") as output:
            json.dump({"line": line, "frames": frames, "heap": heap, "lifetimeEnded": lifetime_ended}, output, ensure_ascii=False)


TrackLifetime()
Snapshot()
