package com.aiagents.highlight.languages

import com.aiagents.highlight.core.Language
import com.aiagents.highlight.languages.bash.bash
import com.aiagents.highlight.languages.c.c
import com.aiagents.highlight.languages.cmake.cmake
import com.aiagents.highlight.languages.cpp.cpp
import com.aiagents.highlight.languages.csharp.csharp
import com.aiagents.highlight.languages.css.css
import com.aiagents.highlight.languages.dart.dart
import com.aiagents.highlight.languages.diff.diff
import com.aiagents.highlight.languages.dockerfile.dockerfile
import com.aiagents.highlight.languages.go.go
import com.aiagents.highlight.languages.glsl.glsl
import com.aiagents.highlight.languages.ini.ini
import com.aiagents.highlight.languages.java.java
import com.aiagents.highlight.languages.javascript.javascript
import com.aiagents.highlight.languages.json.json
import com.aiagents.highlight.languages.kotlin.kotlin
import com.aiagents.highlight.languages.latex.latex
import com.aiagents.highlight.languages.lua.lua
import com.aiagents.highlight.languages.markdown.markdown
import com.aiagents.highlight.languages.php.php
import com.aiagents.highlight.languages.powershell.powershell
import com.aiagents.highlight.languages.properties.properties
import com.aiagents.highlight.languages.python.python
import com.aiagents.highlight.languages.rust.rust
import com.aiagents.highlight.languages.ruby.ruby
import com.aiagents.highlight.languages.sql.sql
import com.aiagents.highlight.languages.swift.swift
import com.aiagents.highlight.languages.typescript.typescript
import com.aiagents.highlight.languages.xml.xml
import com.aiagents.highlight.languages.yaml.yaml

/**
 * Every grammar bundled with the highlighter.
 *
 * Each entry builds a fresh mode tree: compilation mutates modes in place, mirroring `highlight.js`.
 */
internal fun builtinLanguages(): List<Language> = listOf(
    json(),
    ini(),
    cmake(),
    go(),
    glsl(),
    yaml(),
    bash(),
    dockerfile(),
    javascript(),
    typescript(),
    xml(),
    css(),
    dart(),
    java(),
    kotlin(),
    latex(),
    lua(),
    powershell(),
    properties(),
    python(),
    c(),
    cpp(),
    csharp(),
    sql(),
    diff(),
    markdown(),
    rust(),
    ruby(),
    php(),
    swift(),
)
