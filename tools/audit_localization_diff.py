#!/usr/bin/env python3
"""List Java changes that are not just "string literal became a resource lookup".

Every changed line is normalised: string literals, resource lookups (getString / getQuantityString /
R.string / R.plurals) and whitespace are collapsed to a placeholder. Removed and added lines that
normalise to the same text are pure localization; anything left over is printed for human review.
"""
import argparse
import collections
import re
import subprocess

STR = re.compile(r'"(?:[^"\\\n]|\\.)*"')
CALL = re.compile(r'(?:[\w\.]+(?:\(\))?\.)*get(?:Quantity)?String\s*\(\s*(?:com\.orbit\.assistant\.)?R\.(?:string|plurals)\.\w+(?:\s*,[^;]*?)?\)')
REF = re.compile(r'(?:com\.orbit\.assistant\.)?R\.(?:string|plurals)\.\w+')


def norm(line):
    line = CALL.sub('S', line)
    line = REF.sub('S', line)
    line = STR.sub('S', line)
    line = re.sub(r'S(?:\s*\+\s*S)+', 'S', line)   # "a" + "b" or a lookup + a literal
    line = re.sub(r'\s+', '', line)
    return line


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', default='main')
    ap.add_argument('paths', nargs='*', default=['app/src/main/java', 'local/src/main/java'])
    args = ap.parse_args()
    diff = subprocess.check_output(['git', 'diff', '-U0', '--no-color', args.base, '--'] + args.paths, text=True)
    current, removed, added, report = None, collections.Counter(), collections.Counter(), []

    def flush():
        if current is None:
            return
        for k in list(removed):
            n = min(removed[k], added.get(k, 0))
            removed[k] -= n
            added[k] -= n
        left_r = [k for k, v in removed.items() for _ in range(v) if k not in ('', 'S', 'S;', 'S,', 'S)')]
        left_a = [k for k, v in added.items() for _ in range(v) if k not in ('', 'S', 'S;', 'S,', 'S)')]
        if left_r or left_a:
            report.append((current, left_r, left_a))

    for line in diff.splitlines():
        if line.startswith('diff --git'):
            flush()
            current = line.split(' b/')[-1]
            removed, added = collections.Counter(), collections.Counter()
        elif line.startswith('---') or line.startswith('+++') or line.startswith('@@'):
            continue
        elif line.startswith('-'):
            removed[norm(line[1:])] += 1
        elif line.startswith('+'):
            added[norm(line[1:])] += 1
    flush()
    for path, left_r, left_a in report:
        print(f'== {path}: {len(left_r)} removed / {len(left_a)} added lines differ beyond string lookups')
        for r in left_r[:40]:
            print('  -', r[:160])
        for a in left_a[:40]:
            print('  +', a[:160])
    print(f'{len(report)} file(s) need a human look')


if __name__ == '__main__':
    main()
