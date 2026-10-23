#!/usr/bin/env python3
"""把 CSS 里的 @layer 包裹展开成普通规则（就地改写）。

为什么需要：Tailwind v4 把全部样式放在 @layer 里，而 @layer 要 Chromium >= 99。
华为/EMUI 等没有 GMS 的设备 WebView 常停在更早版本，@layer 内容会被整块忽略，
面板只剩系统默认控件外观（背景/文字色全丢）。展开后按源顺序生效，与 layer 的
相对优先级一致（Tailwind 输出顺序即 theme < base < utilities）。

用法: flatten_layers.py <file.css> [...]   已在原地，幂等。
"""
import sys, re

def find_block_end(s, i):
    """s[i] == '{'，返回匹配的 '}' 下标。跳过字符串与注释。"""
    depth = 0
    n = len(s)
    while i < n:
        c = s[i]
        if c == '"' or c == "'":
            q = c; i += 1
            while i < n and s[i] != q:
                i += 2 if s[i] == '\\' else 1
        elif c == '/' and i + 1 < n and s[i+1] == '*':
            j = s.find('*/', i + 2)
            i = n if j < 0 else j + 1
        elif c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1

def flatten(s):
    out = []
    i = 0
    while True:
        m = re.compile(r'@layer\s+([A-Za-z0-9_,\s.-]+?)\s*([{;])').search(s, i)
        if not m:
            out.append(s[i:]); break
        out.append(s[i:m.start()])
        if m.group(2) == ';':          # @layer a, b, c; 只是声明顺序，丢掉
            i = m.end(); continue
        brace = m.end() - 1
        end = find_block_end(s, brace)
        if end < 0:
            out.append(s[m.start():]); break
        inner = s[brace + 1:end]
        out.append(flatten(inner))     # 递归：可能嵌套 @layer
        i = end + 1
    return ''.join(out)

if __name__ == '__main__':
    for path in sys.argv[1:]:
        src = open(path, encoding='utf-8').read()
        if '@layer' not in src:
            print(f'  skip (no @layer): {path}'); continue
        dst = flatten(src)
        assert '@layer' not in dst, f'仍有 @layer 残留: {path}'
        # 健全性：大括号必须配平
        assert dst.count('{') == dst.count('}'), 'brace imbalance'
        open(path, 'w', encoding='utf-8').write(dst)
        print(f'  flattened: {path}  {len(src)} → {len(dst)} B')
