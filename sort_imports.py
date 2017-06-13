#!/usr/bin/env python2

import argparse
import collections
import difflib
import fnmatch
import os
import os.path
import re
import sys

IGNORES = ('.*', 'build')
IMPORT_RE = re.compile(r'import\s+(?:(?P<static>static)\s+)?(?P<type>\w+(?:\.\w+)*(?:\.\*)?)')
SORT_GROUPS = ('android', 'com', 'junit', 'net', 'org', 'java', 'javax', None, 'static')
COMMENT_START = '//'
MULTILINE_COMMENT_START = '/*'
JAVADOC_START = '/**'
COMMENT_END = '*/'
JAVADOC_LINK_RE = re.compile(r'\{@link\b(?:\s+(?:\w+\.)*(?P<type>\w+)).*?\}')
IDENTIFIER_RE = re.compile(r'(?P<type>\w+)(?:\.\w+)*', re.UNICODE)
STRING_RE = re.compile(r'"(?:[^"\\]|\\[btnfr"\'\\]|\\0[0-3]?[0-9]{0,2}|\\u+[0-9A-Fa-f]{4})*"')

def import_group(type):
    if type.startswith('java.') or type.startswith('javax.'):
        return 'java'
    return type.split('.', 1)[0]

def import_group_key(group):
    try:
        return (SORT_GROUPS.index(group),)
    except ValueError:
        return (SORT_GROUPS.index(None), group)

def sort_imports(file, inplace=False, remove_unused=False):
    inlines = file.readlines()
    before_imports = []
    import_groups = collections.defaultdict(dict)
    referenced_types = set()
    after_imports = []
    mode = 'before_imports'
    in_comment = False
    in_javadoc = False
    consecutive_newlines = 0
    for line in inlines:
        if mode == 'before_imports':
            match = IMPORT_RE.match(line.strip())
            if match:
                mode = 'in_imports'
            else:
                before_imports.append(line)
                continue
        elif mode == 'in_imports':
            match = IMPORT_RE.match(line.strip())
            if line.strip() and not match:
                mode = 'after_imports'
        if mode == 'after_imports':
            after_imports.append(line)
            if remove_unused:
                position = 0
                while position < len(line):
                    if in_comment:
                        end = line.find(COMMENT_END, position)
                        if end < 0:
                            end = len(line)
                        if in_javadoc:
                            while position < end:
                                matcher = JAVADOC_LINK_RE.search(line, position, end)
                                if not matcher:
                                    break
                                type = matcher.group('type')
                                if type:
                                    referenced_types.add(type)
                                position = matcher.end()
                        position = end + len(COMMENT_END)
                        in_comment = position > len(line)
                    else:
                        end = len(line)
                        string = STRING_RE.search(line, position, end)
                        if string:
                            end = string.start()
                        identifier = IDENTIFIER_RE.search(line, position, end)
                        if identifier:
                            end = identifier.start()
                        comment = line.find(COMMENT_START, position, end)
                        if comment >= 0:
                            end = comment
                        multiline_comment = line.find(MULTILINE_COMMENT_START, position, end)
                        if multiline_comment >= 0:
                            in_comment = True
                            in_javadoc = line.startswith(JAVADOC_START, multiline_comment)
                            if in_javadoc:
                                position = multiline_comment + len(JAVADOC_START)
                            else:
                                position = multiline_comment + len(MULTILINE_COMMENT_START)
                        elif comment >= 0:
                            break
                        elif identifier:
                            referenced_types.add(identifier.group('type'))
                            position = identifier.end()
                        elif string:
                            position = string.end()
                        else:
                            break
            continue
        if not match:
            consecutive_newlines += 1
            continue
        consecutive_newlines = 0
        type = match.group('type')
        group = import_group(match.group('static') or type)
        import_groups[group if group in SORT_GROUPS else None][type] = line
    sorted_imports = []
    for group in sorted(import_groups.iterkeys(), key=import_group_key):
        nonempty_group = False
        for type, line in sorted(import_groups[group].items()):
            if remove_unused:
                type = type[type.rfind('.') + 1:]
                if type != '*' and type not in referenced_types:
                    continue
            nonempty_group = True
            sorted_imports.append(line)
        if nonempty_group:
            sorted_imports.append('\n')
    sorted_imports.extend('\n' for _ in range(1, consecutive_newlines))
    outlines = before_imports + sorted_imports + after_imports
    if file != sys.stdin or not inplace:
        sys.stdout.writelines(difflib.unified_diff(inlines, outlines, fromfile=file.name, tofile=file.name))
    if inplace:
        if file == sys.stdin:
            sys.stdout.writelines(outlines)
        elif inlines != outlines:
            with open(file.name, 'wb') as out:
                out.writelines(outlines)

def main():
    parser = argparse.ArgumentParser(description='sort java imports')
    parser.add_argument('--inplace', '-i', action='store_true',
            help='apply changes to the file(s)')
    parser.add_argument('--unused', '-u', action='store_true',
            help='remove unused imports')
    parser.add_argument('files', metavar='*.java', nargs='*', help='files to modify')
    args = parser.parse_args()
    if args.files:
        for name in args.files:
            if name == '-':
                sort_imports(sys.stdin, inplace=args.inplace, remove_unused=args.unused)
            else:
                with open(name, 'rb') as file:
                    sort_imports(file, inplace=args.inplace, remove_unused=args.unused)
    else:
        for root, dirs, files in os.walk('.', topdown=True):
            dirs[:] = [dir for dir in dirs if not any(fnmatch.fnmatch(dir, ignore) for ignore in IGNORES)]
            for name in files:
                if name.endswith('.java'):
                    with open(os.path.join(root, name), 'rb') as file:
                        sort_imports(file, inplace=args.inplace, remove_unused=args.unused)

if __name__ == '__main__':
    main()
