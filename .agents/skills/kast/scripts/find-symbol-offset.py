#!/usr/bin/env python3
"""
Compute the zero-based UTF-16 character offset for a symbol in a Kotlin source file.

Usage:
  # From a symbol name — returns all candidate offsets (declaration sites preferred):
  python find-symbol-offset.py <file-path> --symbol <name>

  # From a 1-based line and 0-based column:
  python find-symbol-offset.py <file-path> --line <n> --col <c>

Output (one per match):
  <offset>\t<line>\t<col>\t<context-snippet>

Offset is zero-based UTF-16 code unit count from the start of file, which is
exactly what kast --offset expects.
"""
import sys
import re
import argparse


def utf16_len(s: str) -> int:
    """Count UTF-16 code units (BMP chars = 1, supplementary = 2)."""
    return sum(2 if ord(c) > 0xFFFF else 1 for c in s)


def line_col_to_offset(lines: list[str], line1: int, col0: int) -> int:
    """
    Convert 1-based line / 0-based column to zero-based UTF-16 offset.
    col0 = 0 means the first character of the line.
    """
    offset = 0
    for i, line in enumerate(lines):
        if i + 1 == line1:
            # Count UTF-16 units up to col0 on this line
            offset += utf16_len(line[:col0])
            return offset
        # Each line in the file ends with '\n' (splitlines keeps the rest)
        offset += utf16_len(line) + 1  # +1 for the '\n'
    raise ValueError(f"Line {line1} not found (file has {len(lines)} lines)")


def find_symbol_offsets(lines: list[str], symbol: str) -> list[tuple[int, int, int, str]]:
    """
    Find all occurrences of `symbol` as a whole identifier word.
    Returns list of (offset, line1, col0, snippet).
    Declaration sites (class/fun/val/var/object/interface/typealias) are returned first.
    """
    pattern = re.compile(r'\b' + re.escape(symbol) + r'\b')

    declarations = []
    usages = []

    decl_keywords = re.compile(
        r'\b(class|fun|val|var|object|interface|typealias|enum|sealed|data|abstract|override|companion)\s+'
        + re.escape(symbol) + r'\b'
    )

    for i, line in enumerate(lines):
        for m in pattern.finditer(line):
            col0 = m.start()
            offset = line_col_to_offset(lines, i + 1, col0)
            snippet = line.rstrip()[:120]
            entry = (offset, i + 1, col0, snippet)
            if decl_keywords.search(line):
                declarations.append(entry)
            else:
                usages.append(entry)

    return declarations + usages


def main():
    parser = argparse.ArgumentParser(description="Compute UTF-16 offset(s) in a Kotlin file")
    parser.add_argument("file", help="Absolute path to the .kt file")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--symbol", help="Identifier name to search for")
    group.add_argument("--line", type=int, help="1-based line number")
    parser.add_argument("--col", type=int, default=0,
                        help="0-based column (required with --line, default 0)")
    args = parser.parse_args()

    try:
        with open(args.file, encoding="utf-8") as f:
            content = f.read()
    except FileNotFoundError:
        print(f"error: file not found: {args.file}", file=sys.stderr)
        sys.exit(1)

    # Split preserving line content without the newline character
    lines = content.splitlines()

    if args.symbol:
        results = find_symbol_offsets(lines, args.symbol)
        if not results:
            print(f"error: symbol '{args.symbol}' not found in {args.file}", file=sys.stderr)
            sys.exit(1)
        for offset, line1, col0, snippet in results:
            print(f"{offset}\t{line1}\t{col0}\t{snippet}")
    else:
        offset = line_col_to_offset(lines, args.line, args.col)
        snippet = lines[args.line - 1].rstrip()[:120] if args.line <= len(lines) else ""
        print(f"{offset}\t{args.line}\t{args.col}\t{snippet}")


if __name__ == "__main__":
    main()
