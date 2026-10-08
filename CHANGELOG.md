# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `apply_texture` takes texture offsets, `xOffset` and `yOffset` in cm, and two more texture sources besides `textureName`: `fromTargetType`/`fromTargetId`/`fromSurface` copies the texture already on another wall side or room surface (image, size, angle, scale, offsets and fittingArea), so a texture imported from a photo that is not in the catalog can be reused; `keepTexture: true` keeps the texture on each target surface and changes only the `angle`, `scale`, `xOffset` or `yOffset` given, e.g. to move a stone pattern up a wall by 20 cm without touching its image. Exactly one source per call; given values override the source's, omitted ones are kept (a catalog texture defaults to angle 0, scale 1, offsets 0). `textureName` is therefore no longer a required parameter. Sweet Home 3D 7.5 stores an offset as a fraction of one tile (`Object3DBranch.getTextureAttributes` translates the surface's texture coordinates by `xOffset * scale * width`), so the plugin converts cm at the final scale. On a wall side +`yOffset` moves the pattern up, measured from elevation 0 of the home, and +`xOffset` moves it to the right as seen facing that side, measured from the wall's start (for the default left-to-right oriented textures); on a floor +x is plan +x and +y is plan -y; on a ceiling +y is plan +y. Offsets have no effect on a room texture with fittingArea. Nothing changes unless every targeted surface can be textured; undo with `checkpoint` / `restore_checkpoint`.
- `get_state` and `query_state` report, next to each texture name, `leftSideTextureInfo`/`rightSideTextureInfo` (walls) and `floorTextureInfo`/`ceilingTextureInfo` (rooms): name, catalogId, width, height, angle (degrees), scale, xOffset and yOffset in cm, fittingArea and leftToRightOriented, or null without a texture. `apply_texture` returns the same for its target.
- `query_state` returns only the part of the scene a client asks for (house-model#91): `kinds` (walls, rooms, furniture, doors, levels, labels; `furniture` excludes doors and windows), `level` (id or name), plan `bbox` `[x0, y0, x1, y1]` (an item matches when its plan footprint intersects it; labels by their point), `name` (case-insensitive regex; label text for labels; walls have no name), `visible` (own flag and a viewable level), `fields` (properties to keep; `id` always kept) and `limit` per kind, with `matched` counts and `truncated`. Items are serialised by the same code as `get_state`. On house-model, `get_state` is about 240 KB; the room list with name, area and level is about 5 KB.
- `scripts/sh3d-win-restart.sh` restarts Sweet Home 3D on Windows from WSL without leaving a `[Recovered]` home (house-model#90): refuses while a window shows unsaved changes, closes the windows gracefully and waits for exit, deletes stale `*.recovered` auto-saves, optionally installs a `.sh3p` over `plugins/sh3d-mcp.sh3p` (backup kept outside `plugins/`, which Sweet Home 3D loads whole), relaunches with `sh3d-win` and waits for port 9877. `--dry-run` prints the plan.
- `modify_level` changes an existing level's name, elevation, wall height or floor thickness in place, by id (only the given properties change). house-model used it to set the Upstairs floor to the blueprints' 25.24 cm joist depth.
- `import_furniture` — imports a local 3D model (OBJ with its MTL and `map_Kd` textures beside it, DAE, 3DS, LWS, or a ZIP holding one) as a new piece at a given position, size, angle and level. The model is copied into the home the way Sweet Home 3D's Import furniture wizard does (loaded with `ModelManager`, rewritten with `OBJWriter` into a temporary ZIP), so it is saved inside the .sh3d with its materials and textures. Omitted dimensions use the model's natural size or keep its proportions; `modelRotation` takes `yUp` (default), `zUp` or a 3x3 matrix. glTF/GLB are not supported: Sweet Home 3D 7.5 has no loader for them.
- `replace_model` — swaps the model of an existing piece in place, keeping its size, position and angle (`keepSize`, default) or scaling the new model's proportions to its current width.
- Both tools give every model a content URL never used before, so a re-exported file with the same name cannot render a shape Sweet Home 3D cached earlier.
- `modify_room` takes an optional `points` array (at least 3 `{x, y}` points, finite numbers, in cm) that replaces the room's polygon in place, keeping its id, name, level, textures, colours and visibility flags. Previously the only way to reshape a room was `delete_room` + `create_room_polygon`, which gave it a new id and lost its appearance. Like the other edits it is undone with `checkpoint` / `restore_checkpoint`.

### Fixed
- `save_home` without `filePath` no longer fails on a `[Recovered]` home whose name Sweet Home 3D cleared (house-model#90). After a forced stop, `AutoRecoveryManager` reopens the auto-save and clears its name when the original file is open too, so the home had nowhere to save. The plugin now records the file each home was opened from or saved to in a home property (`com.sh3d.mcp.sourcePath`), which every auto-save carries, and `save_home` falls back to it; the result's `pathSource` says which path was used (`parameter`, `homeName` or `openedFile`). Without a known file the error now says to pass `filePath`. Saving also clears the home's recovered flag, as the app's own Save does, so Sweet Home 3D deletes the stale recovery file.
- `generate_shape` now emits every face wound counter-clockwise seen from outside, so its normals point out of the solid and Sweet Home 3D (which culls back faces) shows it (house-model#69). `extrude` built its caps for a clockwise plan polygon and its sides for a counter-clockwise one, so one or the other always came out inside-out; the polygon is now normalised to one orientation first, and either input order gives the same solid. `stairs`, `pipe`, `torus` and `hemisphere` were inside-out entirely, as were the `cylinder` and `hemisphere` CSG operands and the top cap of the `sphere` operand (angles run from +X towards +Z, which is clockwise seen from above in Java3D's Y-up frame). A new test reads the generated OBJ back and checks every face's normal points out of the solid for each mode.
- `place_door_or_window` and `place_furniture` now create a `HomeDoorOrWindow` when the catalog item is a door or window, instead of a generic `HomePieceOfFurniture` flagged as one. The generic piece lost the catalog's sash definitions (no swing arc in the plan, hinge side not switchable with `mirrored`), the wall cut-out shape and the frame-to-wall metadata, so doors rendered as a narrow leaf floating in an oversized opening. Placed doors are also bound to their wall, as the app does on drop.

### Documentation
- Added a Troubleshooting section documenting the macOS Mac App Store sandbox limitation: that build lacks the `com.apple.security.network.server` entitlement, so the MCP server cannot open its listening port. Use a non-sandboxed Sweet Home 3D build instead. (#2)

## [1.1.0] - 2026-03-13

### Added
- **Multilingual UI** — plugin settings dialog and menu items are now localized for 18 languages: French, German, Spanish, Italian, Russian, Simplified Chinese, Traditional Chinese, Japanese, Portuguese, Brazilian Portuguese, Dutch, Swedish, Czech, Polish, Hungarian, Greek, Bulgarian, Vietnamese
- Plugin name and description in SH3D Plugin Manager are also localized

### Changed
- All UI strings externalized to Java ResourceBundle (`McpPlugin.properties`)
- `McpSettingsDialog` accepts `ResourceBundle` via constructor (dependency injection)
- Dynamic strings use `java.text.MessageFormat` for proper placeholder handling
- Version bumped to 1.1.0

## [1.0.0] - 2026-02-27

### Initial public release

Full-featured MCP server embedded directly into Sweet Home 3D as a plugin.
Exposes 42 tools over Streamable HTTP (JSON-RPC 2.0) on `http://localhost:9877/mcp`,
compatible with the MCP protocol version `2025-03-26`.

### Added

**Scene state**
- `get_state` — full scene snapshot: walls, furniture, rooms, levels, camera
- `clear_scene` — remove all objects from the scene
- `save_home` / `load_home` — persist and restore `.sh3d` files

**Walls**
- `create_wall` — single wall by two endpoints
- `create_walls` — four-wall rectangular room in one call
- `modify_wall` — update height, thickness, color, shininess, arc
- `delete_wall` — remove wall by ID
- `connect_walls` — join two walls for correct corner rendering

**Rooms**
- `create_room_polygon` — room from an arbitrary point polygon
- `modify_room` — update name, floor/ceiling color, shininess, visibility
- `delete_room` — remove room by ID

**Furniture**
- `list_furniture_catalog` — browse the built-in catalog with filtering
- `list_categories` — catalog categories with item counts
- `place_furniture` — place a catalog item at given coordinates
- `modify_furniture` — update position, rotation, size, color, visibility
- `delete_furniture` — remove furniture by ID

**Doors and windows**
- `place_door_or_window` — insert a catalog door/window into a wall by wall ID and position

**Textures**
- `list_textures_catalog` — browse texture catalog with filtering
- `apply_texture` — apply a catalog texture to a wall side, floor, or ceiling

**Levels (floors)**
- `add_level` — create a new floor with elevation and slab thickness
- `list_levels` — list all levels with the currently selected one
- `set_selected_level` — switch the active level
- `delete_level` — remove a level and all its objects

**Cameras and rendering**
- `set_camera` — switch between top-view and observer camera; set position and angles
- `store_camera` — save a named viewpoint
- `get_cameras` — list all saved viewpoints
- `render_photo` — 3D photo-realistic render (Sunflow); optionally save to file
- `set_environment` — configure sky, ground, light intensity, wall transparency, drawing mode

**Export**
- `export_plan_image` — fast 2D floor plan export to PNG
- `export_svg` — 2D floor plan export to SVG
- `export_to_obj` — 3D scene export to Wavefront OBJ (ZIP with OBJ + MTL + textures)

**Annotations**
- `add_label` — text label on the 2D plan
- `add_dimension_line` — measurement annotation on the 2D plan

**Checkpoints (undo timeline)**
- `checkpoint` — take an in-memory snapshot of the scene
- `restore_checkpoint` — restore a snapshot (undo/redo by index or ID)
- `list_checkpoints` — list all snapshots with the current cursor position

**3D shape generation**
- `generate_shape` — create arbitrary 3D geometry: extrude (polygon + height) or mesh (vertices + triangles)

**Utility**
- `batch_commands` — execute multiple commands in a single call

[Unreleased]: https://github.com/grimashevich/sweethome3d-mcp-server/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/grimashevich/sweethome3d-mcp-server/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/grimashevich/sweethome3d-mcp-server/releases/tag/v1.0.0
