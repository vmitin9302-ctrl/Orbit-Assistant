#!/usr/bin/env python3
"""Validate Android localization resources and presentation-only source changes."""
import argparse
import collections
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
LEX = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:[^"\\]|\\.)*"|\'(?:[^\'\\]|\\.)*\'')
FORMAT = re.compile(r'%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])')


def java_value(s):
    return json.loads(s.replace("\\'", "'"))


def android_value(node):
    text = ''.join(node.itertext())
    if text.startswith('"') and text.endswith('"'):
        text = text[1:-1]
    return re.sub(r'\\(.)', lambda m: {'n': '\n', 't': '\t'}.get(m[1], m[1]), text)


def canonical(s):
    # Join Java compile-time string concatenation before token comparison.
    pair = re.compile(r'("(?:[^"\\]|\\.)*")\s*\+\s*("(?:[^"\\]|\\.)*")')
    while pair.search(s):
        s = pair.sub(lambda m: json.dumps(java_value(m[1]) + java_value(m[2]), ensure_ascii=False), s)
    out, last = [], 0
    for m in LEX.finditer(s):
        out.append(re.sub(r'\s+', '', s[last:m.start()]))
        if m[0].startswith('"'):
            out.append(json.dumps(java_value(m[0]), ensure_ascii=False))
        elif m[0].startswith("'"):
            out.append(m[0])
        last = m.end()
    out.append(re.sub(r'\s+', '', s[last:]))
    return ''.join(out)


def resources(path):
    items = {}
    for xml in sorted(path.glob('*.xml')):
        for node in ET.parse(xml).getroot():
            if node.tag not in ('string', 'plurals', 'string-array'):
                continue
            name = node.get('name')
            assert name not in items, f'Duplicate resource: {xml}: {name}'
            items[name] = node
    return items


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', default='main')
    args = parser.parse_args()
    xml_count = 0
    for module in ('app', 'local'):
        for xml in (ROOT / module / 'src').rglob('*.xml'):
            ET.parse(xml)
            xml_count += 1
    en = resources(ROOT / 'app/src/main/res/values')
    ru = resources(ROOT / 'app/src/main/res/values-ru')
    required = {k for k, n in en.items() if n.get('translatable') != 'false'}
    assert required <= set(ru), f'Missing Russian resources: {sorted(required - set(ru))}'
    assert set(ru) <= set(en), f'Missing English fallback: {sorted(set(ru) - set(en))}'
    for key in required:
        a, b = en[key], ru[key]
        assert a.tag == b.tag, key
        if a.tag == 'plurals':
            assert {'one', 'few', 'many', 'other'} <= {n.get('quantity') for n in b}, key
        elif a.tag == 'string-array':
            assert len(a) == len(b), key
        if a.get('formatted') != 'false':
            assert collections.Counter(FORMAT.findall(android_value(a))) == collections.Counter(FORMAT.findall(android_value(b))), key
    changed = subprocess.check_output(['git', 'diff', '--name-only', args.base, '--'], cwd=ROOT, text=True).splitlines()
    checked = []
    for path in changed:
        # No permissions, package/version/build dependency changes are needed for localization.
        if path.endswith('build.gradle'):
            raise AssertionError(f'Unexpected build configuration change: {path}')
        if not path.endswith('.java') or '/src/test/' in path:
            continue
        current = (ROOT / path).read_text()
        original = subprocess.check_output(['git', 'show', f'{args.base}:{path}'], cwd=ROOT, text=True)
        constants = re.findall(r'static final int (\w+) = R\.string\.(ui_\w+);', current)
        restored = current
        for name, key in constants:
            restored = restored.replace('getString(' + name + ')', name)
            restored = restored.replace('static final int ' + name + ' = R.string.' + key + ';',
                                        'static final String ' + name + ' = ' + json.dumps(android_value(en[key]), ensure_ascii=False) + ';')
        restored = re.sub(r'(?:getContext\(\)\.)?getString\(R\.string\.(ui_\w+)\)',
                          lambda m: json.dumps(android_value(en[m[1]]), ensure_ascii=False), restored)
        assert canonical(original) == canonical(restored), f'Non-presentation Java change: {path}'
        checked.append(path)
    print(f'PASS: {xml_count} XML files; {len(required)} Russian resources with English fallback')
    print(f'PASS: {len(checked)} changed Java files restore exactly to baseline tokens after resource expansion')
    print('This is a static check; it does not establish full UI coverage or on-device data preservation.')


if __name__ == '__main__':
    main()
