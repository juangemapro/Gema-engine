# Gema Engine

Gema Engine is an Android-first creative engine project. The long-term goal is a unified 2D/3D creation environment with modeling, animation, rendering, effects, scripting and AI-assisted workflows.

## Current implementation status

This repository is an early prototype, not yet a Blender/Prisma3D replacement.

### Present in the codebase
- Native Android application shell and an OpenGL ES 2.0 viewport.
- Initial cube and sphere drawing and basic add/select/transform UI.
- Shared scene data types for transforms, mesh geometry, materials, cameras and physics bodies.
- Versioned JSON-based `.gme` scene serialization for nodes, transforms, meshes, materials, physics settings, cameras, animation clips and transform keyframes.
- Starter gravity/floor physics stepper.
- Linear transform-key interpolation utility for animation tracks.
- Minimal Wavefront OBJ mesh reader/writer (vertices and polygon faces).
- GitHub Actions workflow configured to build and upload a debug APK on pushes to `main`, pull requests and manual dispatch.

### Not implemented or not yet integrated
- Full modeling tools, mesh editing, selection handles, viewport orbit/pan/zoom and a production scene hierarchy.
- A complete rigid-body physics engine, collision shapes, joints or simulation controls.
- A visual timeline/keyframe editor and animation clip persistence/playback in the UI.
- Skeleton authoring, skinning, weight painting, inverse kinematics or retargeting.
- Full camera/light editing, UV tools, texture painting, node materials or a production renderer.
- A shader editor or a stable Gema Shader Language.
- GLB/glTF, FBX, Blender `.blend` and Prisma project import/export. OBJ support is deliberately limited and does not preserve UVs, normals, materials or rigs.
- Image/video rendering and export, 2D compositing, scripting, plugins or AI agent integration.
- A desktop editor.

## Build

The GitHub Actions workflow uses JDK 17 and Gradle 8.9 to run:

```bash
gradle assembleDebug
```

When the workflow succeeds, it uploads `gema-engine-debug-apk` as a workflow artifact. A configured workflow is not proof of a successful build; check the latest Actions run and its artifact before installing an APK.

## Planned development sequence

1. Make the Android editor stable and verify repeatable APK builds.
2. Integrate scene documents, hierarchy, selection, transforms, cameras and save/load into the UI.
3. Expand geometry tools and implement reliable GLB/glTF import/export.
4. Build animation timeline, skeletons, skinning and animation clips.
5. Add robust physics and collision systems.
6. Add materials, textures, lighting, shader editor and renderer.
7. Add image/video export, 2D/3D composition and project packaging.
8. Evaluate FBX, Blender and Prisma interchange paths, then desktop and AI-assisted workflows.

## Interchange format notes

The `.gme` format is currently a versioned JSON scene document, not yet a complete production asset container. OBJ is a basic geometry interchange path only. FBX and Blender files require dedicated, compatible parsing/conversion pipelines; Prisma compatibility requires a documented format or representative sample files.