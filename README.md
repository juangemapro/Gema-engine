# Gema Engine

Android-first 3D creation engine. This repository begins with a native Android application and an OpenGL ES viewport.

## Milestone 0.1
- Native Android shell and OpenGL ES 2.0 viewport.
- Primitive cube and sphere creation.
- Scene hierarchy and selection.
- Move, rotate and scale controls.
- Basic timeline playback.
- OBJ import/export foundation and JSON-based .gme scene format.
- GitHub Actions workflow that builds and uploads a debug APK.

## Build locally
Install JDK 17 and Android SDK Platform 35, then run:

```bash
gradle assembleDebug
```

Artifact: `app/build/outputs/apk/debug/app-debug.apk`

## Roadmap
1. Mesh editing, cameras, lights, materials and UVs.
2. glTF/GLB import/export.
3. Skeletons, skinning, animation clips and keyframes.
4. Physics, collision shapes and constraints.
5. Gema Shader Language and shader editor.
6. Rendering, compositing, 2D/3D workflows, image/video export.
7. Desktop editor and broader interchange formats.

## Format status
The first milestone's .gme format stores scene metadata and transforms; it is not yet a complete mesh container. OBJ support is the first interchange target. FBX and Blender .blend need dedicated conversion/import pipelines; Prisma project compatibility requires a documented format or sample project files.
