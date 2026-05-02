# erdMaid-private

erdMaid exports selected tables from the IntelliJ Database tool window as Mermaid `erDiagram` syntax.

![Build](https://github.com/luceat-lux-vestra/erdMaid-private/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

## What it does

- Select one or more tables from the IntelliJ Database tool window.
- Export the selected tables as Mermaid `erDiagram` syntax.
- Preserve actual column order from the database metadata.
- Include PK/FK relationships, column types, and column comments.
- Copy the generated diagram to the clipboard and show a notification.

<!-- Plugin description -->
erdMaid exports selected database tables from the IntelliJ Database tool window into Mermaid `erDiagram` syntax, preserving actual column order and including PK/FK metadata, data types, and column comments. The result is copied to the clipboard and a notification is shown.
<!-- Plugin description end -->

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "erdMaid"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/luceat-lux-vestra/erdMaid-private/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
