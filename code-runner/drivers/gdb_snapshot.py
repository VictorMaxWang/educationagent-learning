"""由 GDB 内嵌 Python 加载；仅读取调试信息和内存，不执行学生表达式。"""
import json
import gdb


class Snapshot(gdb.Command):
    def __init__(self):
        super().__init__("xtrace-snapshot", gdb.COMMAND_USER)

    def invoke(self, argument, from_tty):
        heap, seen, frames = {}, set(), []

        def serialize(value, depth=0):
            try:
                if value.is_optimized_out:
                    return {"$unavailable": "值已被编译器优化"}
                kind = value.type.strip_typedefs().code
                typename = str(value.type.strip_typedefs())
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
                    return value["_M_dataplus"]["_M_p"].string(length=min(int(value["_M_string_length"]), 500), errors="replace")
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
                    length = max(0, int(end - begin))
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
                                locals_map[symbol.name] = serialize(frame.read_var(symbol))
                        if block.function:
                            break
                        block = block.superblock
                    frames.append({"name": frame.name() or "<main>", "locals": locals_map})
            except BaseException:
                pass
            frame = frame.older()
        with open(argument.strip(), "w", encoding="utf-8") as output:
            json.dump({"line": line, "frames": frames, "heap": heap}, output, ensure_ascii=False)


Snapshot()
