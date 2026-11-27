# Gema Engine 0.2.0

Gema Engine is an Android-first 3D editor prototype with a roadmap toward a combined 2D/3D multimedia development environment. The interface is organized around a compact mobile workspace: project home, viewport, hierarchy, tool panels, timeline and export actions.

> **Status:** functional early editor prototype. It is not a complete replacement for Blender or Prisma3D. Capabilities below distinguish working features from UI/data foundations and unsupported import paths.

## Included in 0.2.0

### Mobile editor and viewport
- Android app with project home and editor workspace.
- OpenGL ES 2.0 viewport, grid, camera orbit by one-finger drag and zoom by pinch.
- Scene hierarchy with selection, rename, duplicate and delete.
- Primitive creation: cube, sphere, plane, cylinder, cone and torus.
- Transform tools for position, rotation and scale, including exact numeric editing.
- Per-object base color and stored roughness/metallic values.
- Scene cameras and point/directional light records.
- Basic rig hierarchy with parented bones and visible bone proxy meshes.

### Animation and physics
- Animation clips, transform tracks, keyframe insertion and linear interpolation.
- Timeline scrubber and playback.
- Basic gravity, mass field, dynamic/static toggle and floor bounce simulation.

### Project data and assets
- Versioned `.gme` project serialization for scene nodes, mesh geometry, transforms, materials, physics properties, cameras, animation clips/keyframes, rig bones, lights, texture URI references, effect settings and 2D layer metadata.
- OBJ import/export for vertices and polygon faces.
- glTF 2.0 / GLB import for the first mesh primitive's FLOAT VEC3 positions and supported unsigned indices.
- glTF/GLB export for the selected mesh.
- PNG viewport capture through Android PixelCopy.
- Android Storage Access Framework file pickers; no broad storage permission is required.

### Shader and project panels
- In-app GLSL ES 1.00 fragment shader editor.
- Shader compilation/linking on the device and application to the viewport.
- Example toon shader.
- Project panels for rendering/effect settings, 2D layers and asset inventory.

## Important limitations in this build

These features have panels or data structures but are **not yet complete production systems**:
- Physics is a starter gravity/floor-bounce simulation, not a full rigid-body engine; mass does not yet affect acceleration and there are no collision shapes, constraints or joints.
- Rig bones are stored in a hierarchy and represented by proxy meshes; vertex weights, skinning deformation, IK, weight painting and retargeting are not implemented.
- Animation currently interpolates object transforms linearly. There is no curve editor, per-channel dope sheet, skeletal animation or animation export.
- Material roughness/metallic values are stored but the viewport renderer is currently unlit and does not evaluate a PBR lighting model.
- Textures can be referenced by URI, but UV mapping, image decoding, texture sampling and texture painting are not integrated.
- Cameras and lights are saved as project data, but camera switching and scene lighting are not yet a complete render pipeline.
- Bloom, vignette, fog, wireframe, outlines, shadows, AO and render quality are stored as settings; post-processing is not yet implemented.
- The 2D panel stores layer metadata only. It does not yet contain a 2D drawing/compositing canvas.
- PNG capture is available. MP4/video export is shown in the interface but the encoder and frame-sequence renderer are not implemented.
- glTF/GLB support is deliberately limited to a first primitive and embedded buffer data. External .gltf buffers, multiple primitives, materials, normals, UVs, morph targets, rigs and animation import are not handled.
- OBJ support does not currently preserve UVs, normals, material libraries or rigs.
- FBX, Blender `.blend`, and Prisma files are recognized and produce an explanatory unsupported-format message; they are not imported in this build.
- The shader editor currently accepts GLSL ES fragment code using the viewport's `aPosition`, `uMvp` and `uColor` shader interface. A dedicated Gema Shader Language and node graph are future work.
- The application is Android-only; desktop editor, scripting runtime, plugin system and AI tools are roadmap items.

## Build and release

GitHub Actions builds a debug APK on pushes to `main`, pull requests and manual dispatch. The workflow verifies that the APK exists, uploads a workflow artifact named `gema-engine-debug-apk`, and publishes a prerelease containing `app-debug.apk` for pushes to `main`.

Local build requirements: JDK 17, Android SDK Platform 35 and Gradle 8.9.

```bash
gradle --no-daemon assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Roadmap

1. Improve mobile editor ergonomics, persistence, undo/redo and viewport selection/gizmos.
2. Complete mesh editing, normals, UVs, texture import and PBR materials.
3. Integrate real glTF scene hierarchy, materials, multiple primitives, rigs and animation clips.
4. Implement skeleton skinning, weight painting, IK and robust physics.
5. Add a true 2D canvas and combined 2D/3D compositor.
6. Add render pipeline, lighting, shadows, post-processing and MP4 export.
7. Build the Gema Shader Language, node graph and scripting system.
8. Evaluate FBX, Blender and Prisma interchange bridges, followed by desktop and AI-assisted workflows.
