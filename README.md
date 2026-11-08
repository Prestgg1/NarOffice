# NarOffice

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

**A simple, fast and free office suite for Android.** View and edit Word, Excel and
PowerPoint files and read PDFs on your phone — no account, no ads, no tracking, no internet access.

NarOffice is written from scratch in Kotlin and Jetpack Compose. It does not bundle a
desktop office suite: it reads the Office Open XML files directly, so it starts fast,
stays small and keeps your files intact. Only the parts you change are rewritten; everything
else in the file (styles, charts, macros, comments, metadata…) is saved exactly as it was.

## Features

**Documents (.docx)**
- Reflowing, mobile-friendly reading view with pinch to zoom
- Rich editing: bold, italic, underline, strikethrough, super/subscript, font size,
  text colour, highlight
- Paragraph styles (title, headings), alignment, bulleted and numbered lists, indents
- Tables (insert, add/remove rows and columns), pictures, page breaks
- Find, find and replace, undo/redo

**Spreadsheets (.xlsx, .csv)**
- Fast canvas grid with frozen panes, merged cells, fills, borders and number formats
- Formula engine with 60+ functions (SUM, IF, VLOOKUP, SUMIF, INDEX/MATCH, TEXT, dates…)
- Cell formatting, number formats, insert/delete rows and columns, multiple sheets
- Copy/paste ranges, AutoSum, sum/average/count of the selection, find

**Presentations (.pptx)**
- Accurate slide rendering (themes, layouts, placeholders, shapes, pictures, tables)
- Edit text, move and resize objects, add text boxes and pictures
- Add, duplicate, reorder and delete slides; full-screen slideshow

**PDF**
- Fast, sharp page rendering with pinch to zoom
- Page indicator and go to page
- Text search on Android 15 and newer
- Print and share

**Everywhere**
- Open files from any app or file manager, save in place or as a copy, share
- Print or save as PDF
- Recent files with pinning
- Light and dark theme, English and Azerbaijani interface

Legacy binary formats (.doc, .xls, .ppt) are not supported.

## Building

```sh
./gradlew assembleRelease
```

Requires JDK 17+ and the Android SDK (compileSdk 37). There are no native libraries and no
proprietary dependencies.

## License

NarOffice is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License, version 3 or (at your option) any later version. See [LICENSE](LICENSE).
