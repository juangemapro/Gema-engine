# Gema Engine 0.3.0-dev

Gema Engine is an Android-first 3D editor prototype and the first stage of a broader multimedia engine. The current development target is a comfortable mobile modeling and animation workflow, with glTF/GLB as the primary interchange format.

> **Development status:** source work is in progress. The current 0.3.0-dev changes have intentionally not been compiled or device-tested yet. Do not treat this branch as a verified Prisma3D replacement. The next APK will only be produced when the project owner explicitly authorizes a build.

## Current editor foundations

### Mobile workspace and viewport
- Android-native editor with scene hierarchy, tool tabs, timeline and export panels.
- OpenGL ES 2.0 viewport source with grid, camera orbit, pinch zoom, two-finger pan, view presets and adaptive focus framing.
- Move, rotate and scale gizmos with screen-space axis handles, rotation rings and scale handles.
- Local/world transform-space control, snapping, numeric transform editing and undo/redo.
- Touch targets and viewport panel sizing adjusted for smaller screens.
- Primitive creation and basic vertex/face editing tools.

### Rendering and materials
- OpenGL ES rendering pipeline source with depth testing, material uniforms, scene lights and studio fallback lighting.
- GGX microfacet PBR shader source with roughness, metallic, Fresnel, normal, occlusion and emissive map inputs.
- Base-color, normal, metallic/roughness, occlusion and emissive texture upload paths.
- Texture decoding bounded by device GPU texture limits, a limited GPU texture cache and mipmaps for power-of-two images.
- Wireframe and selected-object edge overlays; fog and vignette shader controls.
- These renderer changes are source-level work and still require an authorized build plus device verification.

### Scene, rigs and animation
- Scene hierarchy with parent relationships and multiple mesh parts.
- glTF skin metadata, inverse bind matrices, four joint influences per vertex and a CPU skinning path.
- Bone proxy hierarchy creation that does not depend on the order of joints in the source file.
- Transform animation clips and timeline playback/scrubbing.
- Basic physics simulation foundations; this is not a full rigid-body engine.

### Import and export
- glTF 2.0 / GLB: scene-node traversal, multiple primitives, mesh attributes, PBR material factors, embedded and external images, external buffers, skins, four-weight skinning data and transform animation channels.
- Sparse glTF accessors and the EXT_texture_webp texture source are handled by the importer.
- External .gltf resources can be resolved by selecting the model's containing asset folder.
- OBJ / MTL: multiple object/material groups, triangulated faces, UVs, normals, diffuse/normal/emissive/roughness/metallic/occlusion map references and texture files. Quoted paths and common MTL map options are handled.
- STL: ASCII and binary triangle mesh import; ASCII export.
- PLY: ASCII vertex/face import and export.
- FBX: partial ASCII FBX geometry and hierarchy import. Binary FBX is not supported.
- Scene export to glTF/GLB and OBJ scene bundles, plus .gme project save/load.
- PNG viewport capture and 2D sketch export.

## Known gaps before Prisma3D-level parity

These are still required or need substantial validation:
- Build verification and testing on real Android devices, including low-memory and mid-range GPUs.
- Complete transform-space correctness for rotation and scale, pivot/origin tools, orthographic camera and more robust selection.
- Full UV editor, UV channel selection, texture painting, texture sampler/wrap metadata and broader material extensions.
- More complete glTF behavior: additional vertex attribute sets, morph targets, advanced animation interpolation, more texture extensions and edge-case validation.
- More complete rig editing, weight painting, IK, retargeting and GPU skinning.
- Binary FBX support and additional interchange formats. Blender .blend files require a conversion/interchange pipeline; they are not read directly.
- Shadows, environment maps, screen-space ambient occlusion, bloom and a fuller post-processing/render pipeline.
- 2D/3D compositing, vector and text layers, video timeline and MP4 export.
- Broader mesh tools, multi-object workflows, project asset management and performance profiling.

Gema Engine is not currently a replacement for Prisma3D, Blender or a general-purpose game engine. Feature presence in the source does not imply full production readiness.

## Build and release policy

GitHub Actions is **manual-only** (workflow_dispatch). Pushing source changes does not start a build or publish a release. The workflow builds a debug APK, verifies it exists and uploads the `gema-engine-debug-apk` artifact. It publishes a prerelease only when manually run against `main`.

### Local build (recommended)

Requirements: JDK 17, Gradle 8.9, Android SDK Platform 35, and `ANDROID_SDK_ROOT` (or `ANDROID_HOME`) set to your SDK directory. This repository currently has no Gradle Wrapper, so Gradle 8.9 must be installed and available on `PATH`.

See [BUILDING.md](BUILDING.md) for complete setup instructions.

**Windows:**
```bat
scripts\build-debug.bat
```

**Linux/macOS:**
```bash
chmod +x scripts/build-debug.sh
./scripts/build-debug.sh
```

Or run Gradle directly:

    gradle --no-daemon clean assembleDebug

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

The result is a debug APK, not a signed production release. Compilation still needs real-device verification.

## Long-term direction

1. Reach a reliable Prisma3D-like mobile modeling and animation workflow.
2. Complete materials, texture workflows, rigging, animation and rendering.
3. Combine 2D and 3D creation and compositing.
4. Expand into a local multimedia development environment with scripting and shader tools.
5. Add an AI assistant that can operate and extend the engine through explicit, safe tools.
