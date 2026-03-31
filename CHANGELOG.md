<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Per-document jump history changelog

## [Unreleased]
- Removed `Shift` handling, can be done natively by binding the original 'Back/Forward' actions to the same shortcuts, but with `Shift`

## [0.0.8]

### Changed
- Extended compatibility to include IntelliJ Platform version 261 (2026.1)

## [0.0.7]

### Fixed
- Fixed compatibility with IntelliJ IDEA 2024.3+: removed usage of deleted IdeEventQueue.addDispatcher API

## [0.0.6]

### Fixed
- Fixed NullPointerException in ForwardInDocumentAction and BackInDocumentAction when no project is available

## [0.0.5]

### Changed
- Made plugin compatible with IntelliJ Platform version 253 (2025.3)

## [0.0.4]

### Fixed
- [issues/92](https://github.com/eprst/ij-per-document-history/issues/92): history is now tracked per editor. This means
each split gets its own history if the same document is opened in multiple splits.

## [0.0.3]

### Fixed
- `pluginUntilBuild` takes effect.
- `pluginVerfication` now works.

## [0.0.2]
Initial publicly released version.