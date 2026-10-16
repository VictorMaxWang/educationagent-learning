import type { CodeLanguage } from './code-api';

export interface CodeExample {
  id: string;
  title: string;
  goal: string;
  code: string;
  stdin: string;
  expectedStdout: string;
  hints: string[];
}

export const CODE_LANGUAGES: { id: CodeLanguage; label: string; fileName: string }[] = [
  { id: 'javascript', label: 'JavaScript', fileName: 'main.js' },
  { id: 'python', label: 'Python', fileName: 'main.py' },
  { id: 'cpp', label: 'C++', fileName: 'main.cpp' },
  { id: 'java', label: 'Java', fileName: 'Main.java' },
];

const goals = {
  area: { title: '菜园面积', goal: '读入菜园的长和宽，观察两个数如何变成面积。', stdin: '4 3', expectedStdout: '12', hints: ['输入中依次给出长和宽。', '面积等于长乘宽，先找到两个变量。', '把输入改为 5 2，再观察面积和输出怎样变化。'] },
  sum: { title: '循环累计', goal: '从 1 加到输入的数，逐步观察累计值的变化。', stdin: '5', expectedStdout: '15', hints: ['累计值从 0 开始。', '每一轮把当前的数加入累计值。', '检查最后一轮是否包含输入的数，试试把 5 改为 6。'] },
  sort: { title: '数组排序', goal: '用冒泡排序比较相邻数，观察数组怎样逐渐有序。', stdin: '8 3 6 1', expectedStdout: '1 3 6 8', hints: ['每次比较相邻的两个数。', '左边较大时交换，较大的数会向右移动。', '观察每一轮结束时右侧已排好的部分，再试着加入重复的数。'] },
  recursion: { title: '递归求和', goal: '观察 sum(5) 一层层调用，再逐层返回的过程。', stdin: '5', expectedStdout: '15', hints: ['先找出递归的停止条件。', 'sum(n) 等于 n 加上 sum(n - 1)。', '在调用栈中观察参数 n，再看返回时每一层怎样相加。'] },
};

function example(id: keyof typeof goals, code: string): CodeExample { return { id, ...goals[id], code }; }

/** 四种语言使用相同题意与输入，便于比较语法；运行与轨迹始终由实际代码产生。 */
export const CODE_EXAMPLES: Record<CodeLanguage, CodeExample[]> = {
  javascript: [
    example('area', `const fs = require('node:fs');
const input = fs.readFileSync(0, 'utf8').trim().split(/\\s+/).map(Number);
const length = input[0];
const width = input[1];
const area = length * width;
console.log(area);`),
    example('sum', `const fs = require('node:fs');
const n = Number(fs.readFileSync(0, 'utf8').trim());
let total = 0;
for (let i = 1; i <= n; i++) {
  total += i;
}
console.log(total);`),
    example('sort', `const fs = require('node:fs');
const numbers = fs.readFileSync(0, 'utf8').trim().split(/\\s+/).map(Number);
for (let end = numbers.length - 1; end > 0; end--) {
  for (let i = 0; i < end; i++) {
    if (numbers[i] > numbers[i + 1]) {
      const temporary = numbers[i];
      numbers[i] = numbers[i + 1];
      numbers[i + 1] = temporary;
    }
  }
}
console.log(numbers.join(' '));`),
    example('recursion', `const fs = require('node:fs');
function sum(n) {
  if (n <= 0) return 0;
  const smaller = sum(n - 1);
  const result = n + smaller;
  return result;
}
const n = Number(fs.readFileSync(0, 'utf8').trim());
const answer = sum(n);
console.log(answer);`),
  ],
  python: [
    example('area', `length, width = map(int, input().split())
area = length * width
print(area)`),
    example('sum', `n = int(input())
total = 0
for i in range(1, n + 1):
    total += i
print(total)`),
    example('sort', `numbers = list(map(int, input().split()))
for end in range(len(numbers) - 1, 0, -1):
    for i in range(end):
        if numbers[i] > numbers[i + 1]:
            temporary = numbers[i]
            numbers[i] = numbers[i + 1]
            numbers[i + 1] = temporary
print(*numbers)`),
    example('recursion', `def sum_numbers(n):
    if n <= 0:
        return 0
    smaller = sum_numbers(n - 1)
    result = n + smaller
    return result

n = int(input())
answer = sum_numbers(n)
print(answer)`),
  ],
  cpp: [
    example('area', `#include <iostream>
int main() {
    int length, width;
    std::cin >> length >> width;
    int area = length * width;
    std::cout << area << '\\n';
    return 0;
}`),
    example('sum', `#include <iostream>
int main() {
    int n;
    std::cin >> n;
    int total = 0;
    for (int i = 1; i <= n; i++) {
        total += i;
    }
    std::cout << total << '\\n';
    return 0;
}`),
    example('sort', `#include <iostream>
#include <sstream>
#include <string>
#include <vector>
int main() {
    std::string line;
    std::getline(std::cin, line);
    std::istringstream input(line);
    std::vector<int> numbers;
    int value;
    while (input >> value) numbers.push_back(value);
    for (int end = (int)numbers.size() - 1; end > 0; end--) {
        for (int i = 0; i < end; i++) {
            if (numbers[i] > numbers[i + 1]) {
                int temporary = numbers[i];
                numbers[i] = numbers[i + 1];
                numbers[i + 1] = temporary;
            }
        }
    }
    for (int i = 0; i < (int)numbers.size(); i++) {
        if (i > 0) std::cout << ' ';
        std::cout << numbers[i];
    }
    std::cout << '\\n';
    return 0;
}`),
    example('recursion', `#include <iostream>
int sum(int n) {
    if (n <= 0) return 0;
    int smaller = sum(n - 1);
    int result = n + smaller;
    return result;
}
int main() {
    int n;
    std::cin >> n;
    int answer = sum(n);
    std::cout << answer << '\\n';
    return 0;
}`),
  ],
  java: [
    example('area', `import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner input = new Scanner(System.in);
        int length = input.nextInt();
        int width = input.nextInt();
        int area = length * width;
        System.out.println(area);
    }
}`),
    example('sum', `import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner input = new Scanner(System.in);
        int n = input.nextInt();
        int total = 0;
        for (int i = 1; i <= n; i++) {
            total += i;
        }
        System.out.println(total);
    }
}`),
    example('sort', `import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner input = new Scanner(System.in);
        String[] pieces = input.nextLine().trim().split("\\\\s+");
        int[] numbers = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            numbers[i] = Integer.parseInt(pieces[i]);
        }
        for (int end = numbers.length - 1; end > 0; end--) {
            for (int i = 0; i < end; i++) {
                if (numbers[i] > numbers[i + 1]) {
                    int temporary = numbers[i];
                    numbers[i] = numbers[i + 1];
                    numbers[i + 1] = temporary;
                }
            }
        }
        for (int i = 0; i < numbers.length; i++) {
            if (i > 0) System.out.print(" ");
            System.out.print(numbers[i]);
        }
        System.out.println();
    }
}`),
    example('recursion', `import java.util.Scanner;
public class Main {
    static int sum(int n) {
        if (n <= 0) return 0;
        int smaller = sum(n - 1);
        int result = n + smaller;
        return result;
    }
    public static void main(String[] args) {
        Scanner input = new Scanner(System.in);
        int n = input.nextInt();
        int answer = sum(n);
        System.out.println(answer);
    }
}`),
  ],
};
