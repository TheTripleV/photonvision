# Camera-Pipeline Decoupling Implementation Plan

## Executive Summary

This document outlines a comprehensive plan to refactor PhotonVision's architecture from a 1-to-1 camera-pipeline model to a fully decoupled system where pipelines are top-level entities that can reference any camera.

**Current Architecture**: Cameras own pipelines, camera settings stored in pipeline configs
**Target Architecture**: Cameras and pipelines are independent, pipelines reference cameras by name

---

## Table of Contents

1. [Current Architecture Analysis](#current-architecture-analysis)
2. [Target Architecture](#target-architecture)
3. [Implementation Phases](#implementation-phases)
4. [File Changes](#file-changes)
5. [Risks and Mitigation](#risks-and-mitigation)
6. [Migration Strategy](#migration-strategy)
7. [Implementation Checklist](#implementation-checklist)

---

## Current Architecture Analysis

### Core Components

**VisionModule** (`photon-core/src/main/java/org/photonvision/vision/processes/VisionModule.java`)
- The "God Class" that couples cameras and pipelines together
- Contains:
  - `PipelineManager pipelineManager` - manages pipelines for THIS camera
  - `VisionSource visionSource` - represents the physical camera
  - `VisionRunner visionRunner` - processes frames
  - Input/output streams (MJPEG consumers) tied to this camera
  - Stream ports: `1181 + (streamIndex * 2)` for input, `+1` for output

**Current Coupling**
- 1:1 mapping: Each VisionModule = 1 Camera + 1 PipelineManager
- PipelineManager holds multiple CVPipelineSettings (only one active)
- When switching pipelines, camera settings are loaded from the pipeline settings

### Camera Settings Currently in Pipeline Settings

From `CVPipelineSettings.java` (lines 43-63):
```java
public boolean cameraAutoExposure = false;
public double cameraExposureRaw = 20;
public double cameraMinExposureRaw = 1;
public double cameraMaxExposureRaw = 100;
public int cameraBrightness = 50;
public int cameraGain = 75;
public int cameraRedGain = 11;
public int cameraBlueGain = 20;
public int cameraVideoModeIndex = 0;
public boolean cameraAutoWhiteBalance = false;
public double cameraWhiteBalanceTemp = 4000;
```

### Configuration Persistence

`CameraConfiguration` stores:
- Camera metadata: uniqueName, nickname, FOV, calibrations, cameraQuirks
- Pipeline settings: `List<CVPipelineSettings> pipelineSettings`
- Driver mode settings: `DriverModePipelineSettings driveModeSettings`
- Current state: `currentPipelineIndex`, `streamIndex`

---

## Target Architecture

### New Separation of Concerns

**Camera Settings (Hardware-Specific)**
These control physical camera hardware, stored at camera level:
- Video mode/resolution: `cameraVideoModeIndex`
- Exposure: `cameraAutoExposure`, `cameraExposureRaw`, min/max
- Brightness: `cameraBrightness`
- Gain: `cameraGain`, `cameraRedGain`, `cameraBlueGain`
- White balance: `cameraAutoWhiteBalance`, `cameraWhiteBalanceTemp`

**Pipeline Settings (Processing-Specific)**
These control image processing, remain in pipeline settings:
- Processing parameters: HSV thresholds, contour filtering, etc.
- Pipeline metadata: `pipelineNickname`, `pipelineType`
- Output settings: `outputShouldShow`, `outputShouldDraw`, `ledMode`
- Frame processing: `inputImageRotationMode`, `streamingFrameDivisor`
- 3D settings: `solvePNPEnabled`, `targetModel`
- **NEW**: `sourceCameraUniqueName` - which camera to pull from

### New Component Architecture

```
CameraManager (new)
├── Camera instances (VisionSource + settings)
│   ├── CameraSettings (new class)
│   │   ├── videoModeIndex
│   │   ├── exposure settings
│   │   ├── brightness, gain, white balance
│   │   └── streams (input/output)
│   └── VisionSource (frame provider)

GlobalPipelineManager (new)
├── Pipeline instances (independent of cameras)
│   ├── CVPipelineSettings
│   │   ├── processing parameters
│   │   ├── sourceCameraUniqueName (new)
│   │   └── NO camera-specific settings
│   └── CVPipeline (processing logic)

VisionModule (refactored)
├── Connects Camera to Pipeline for processing
└── Manages frame flow: Camera → Pipeline → Output
```

---

## Implementation Phases

### PHASE 1: Create New Data Structures

**1.1: Create CameraSettings Class**
- **File**: `photon-core/src/main/java/org/photonvision/common/configuration/CameraSettings.java` (NEW)
- **Purpose**: Store camera-specific hardware settings
- **Fields**:
  ```java
  public int videoModeIndex = 0;
  public boolean autoExposure = false;
  public double exposureRaw = 20;
  public double minExposureRaw = 1;
  public double maxExposureRaw = 100;
  public int brightness = 50;
  public int gain = 75;
  public int redGain = 11;
  public int blueGain = 20;
  public boolean autoWhiteBalance = false;
  public double whiteBalanceTemp = 4000;
  ```

**1.2: Add sourceCameraUniqueName to CVPipelineSettings**
- **File**: `photon-core/src/main/java/org/photonvision/vision/pipeline/CVPipelineSettings.java`
- **Changes**:
  - Add `public String sourceCameraUniqueName = "";`
  - Mark all `camera*` fields as `@Deprecated`

**1.3: Update CameraConfiguration**
- **File**: `photon-core/src/main/java/org/photonvision/common/configuration/CameraConfiguration.java`
- **Change**: Add `public CameraSettings cameraSettings = new CameraSettings();`

---

### PHASE 2: Create Camera Manager

**2.1: Create CameraManager Class**
- **File**: `photon-core/src/main/java/org/photonvision/vision/processes/CameraManager.java` (NEW)
- **Purpose**: Manage all cameras independently of pipelines
- **Responsibilities**:
  - Hold all VisionSource instances
  - Manage camera settings
  - Handle camera streams
  - Provide frame access to pipelines
  - Track active camera configurations

**2.2: Create Camera Class**
- **File**: `photon-core/src/main/java/org/photonvision/vision/processes/Camera.java` (NEW)
- **Purpose**: Represent a single camera instance
- **Components**:
  ```java
  private final VisionSource visionSource;
  private final CameraSettings settings;
  private final MJPGFrameConsumer inputStreamer;
  private final FileSaveFrameConsumer inputSaver;
  private final String uniqueName;
  ```

---

### PHASE 3: Refactor Pipeline Management

**3.1: Make PipelineManager Camera-Independent**
- **File**: `photon-core/src/main/java/org/photonvision/vision/processes/PipelineManager.java`
- **Changes**:
  - Remove dependency on specific camera
  - Keep pipeline settings and pipeline instantiation logic
  - Make it reusable across different contexts

**3.2: Create GlobalPipelineManager**
- **File**: `photon-core/src/main/java/org/photonvision/vision/processes/GlobalPipelineManager.java` (NEW)
- **Purpose**: Manage all pipelines across all cameras
- **Features**:
  - Create, delete, duplicate pipelines
  - Each pipeline references a camera by uniqueName
  - Pipeline activation/deactivation
  - Multiple pipelines can reference the same camera

---

### PHASE 4: Refactor VisionModule

**4.1: Refactor VisionRunner for Camera-Pipeline Pairing**
- **File**: `photon-core/src/main/java/org/photonvision/vision/processes/VisionRunner.java`
- **Changes**:
  - Accept Camera and Pipeline as inputs
  - Process frames from Camera through Pipeline
  - Output results

**4.2: Create Output Stream Manager**
- **File**: `photon-core/src/main/java/org/photonvision/vision/processes/OutputStreamManager.java` (NEW)
- **Purpose**: Aggregate output from multiple pipelines for a single camera
- **Features**:
  - Collect results from all pipelines targeting a camera
  - Draw all objects onto output frame
  - Stream aggregated output

**4.3: Refactor VisionModule**
- **Recommended Approach**: Keep VisionModule as orchestrator (minimal changes)
  - Modify to accept Camera from CameraManager
  - Modify to accept Pipeline(s) from GlobalPipelineManager
  - Remove camera settings management from pipeline switching logic

---

### PHASE 5: Update Stream Management

**5.1: Move Input Streams to Camera**
- **File**: `photon-core/src/main/java/org/photonvision/vision/processes/Camera.java`
- **Change**: Associate input streams with Camera instances
- **Port calculation**: Keep current logic `1181 + (streamIndex * 2)`

**5.2: Implement Output Stream Aggregation**
- **File**: `photon-core/src/main/java/org/photonvision/vision/processes/OutputStreamManager.java`
- **Logic**:
  - Collect all TrackedTargets from pipelines targeting this camera
  - Draw all targets onto single output frame
  - Stream aggregated result on output port `1181 + (streamIndex * 2) + 1`

---

### PHASE 6: Update API and WebSocket Handlers

**6.1: Update DataChangeEvent Routing**
- **File**: `photon-core/src/main/java/org/photonvision/vision/processes/VisionModuleChangeSubscriber.java`
- **Changes**:
  - Route camera settings changes to Camera
  - Route pipeline settings changes to Pipeline
  - Handle new `sourceCameraUniqueName` field

**6.2: Update REST API Endpoints**
- **File**: `photon-server/src/main/java/org/photonvision/server/RequestHandler.java`
- **Changes**:
  - Add endpoints for camera settings (separate from pipeline)
  - Add endpoints for pipeline settings (including camera selection)
  - Update calibration endpoints to work with cameras
  - Add endpoint to list available cameras for pipeline selection

**6.3: Update WebSocket Messages**
- **Files**:
  - `photon-core/src/main/java/org/photonvision/common/dataflow/websocket/UICameraConfiguration.java`
  - `photon-core/src/main/java/org/photonvision/common/dataflow/websocket/UIDataPublisher.java`
- **Changes**:
  - Separate camera settings from pipeline settings in UI messages
  - Add camera selection to pipeline configuration messages

---

### PHASE 7: Update Frontend

**7.1: Update TypeScript Types**
- **Files**:
  - `photon-client/src/types/SettingTypes.ts`
  - `photon-client/src/types/PipelineTypes.ts`
- **Changes**:
  - Separate CameraSettings from PipelineSettings
  - Add sourceCameraUniqueName to pipeline types

**7.2: Update Camera Settings Store**
- **File**: `photon-client/src/stores/settings/CameraSettingsStore.ts`
- **Changes**:
  - Separate camera settings actions from pipeline settings
  - Add camera selection to pipeline configuration
  - Update websocket message handling

**7.3: Update UI Components**

**Camera Settings Screen**:
- Show camera-specific settings (exposure, gain, white balance, etc.)
- Remove these settings from pipeline view

**Pipeline Settings Screen**:
- Add camera source selector dropdown (list of available cameras)
- Show only processing settings
- "Input" tab behavior:
  - Find associated camera via `sourceCameraUniqueName`
  - Modify that camera's settings (not pipeline settings)

**Dashboard View**:
- Update to handle camera-pipeline selection
- Show which pipelines are using which cameras

---

### PHASE 8: Migration and Backwards Compatibility

**8.1: Create Configuration Migration**
- **File**: `photon-core/src/main/java/org/photonvision/common/configuration/ConfigMigrator.java` (NEW)
- **Purpose**: Migrate old configs to new format
- **Migration Logic**:
  ```
  For each CameraConfiguration:
    1. Extract camera settings from first pipeline (or use defaults)
    2. Create new CameraSettings with extracted values
    3. Remove camera settings from all pipeline settings
    4. Add current camera's uniqueName to all its pipelines as sourceCameraUniqueName
  ```

**8.2: Handle Version Mismatch**
- **File**: `photon-core/src/main/java/org/photonvision/common/configuration/ConfigManager.java`
- **Changes**:
  - Add version checking
  - Automatic migration on startup
  - Create backup of old config before migration
  - Rollback capability if migration fails

---

### PHASE 9: Update NetworkTables Publishing

**9.1: Update NT Publisher**
- **File**: `photon-core/src/main/java/org/photonvision/common/dataflow/networktables/NTDataPublisher.java`
- **Changes**:
  - Ensure pipeline results still published correctly
  - Handle multiple pipelines per camera if needed
  - Maintain backwards compatibility with existing NT structure

---

### PHASE 10: Testing Strategy

**10.1: Unit Tests**
- Test CameraSettings serialization/deserialization
- Test PipelineSettings with sourceCameraUniqueName
- Test configuration migration logic
- Test Camera class
- Test CameraManager
- Test GlobalPipelineManager

**10.2: Integration Tests**
- Test camera switching between pipelines
- Test multiple pipelines using same camera
- Test stream aggregation with multiple pipelines
- Test calibration with new structure
- Test pipeline duplication

**10.3: Migration Tests**
- Test migrating old configurations
- Test backwards compatibility
- Test edge cases:
  - Missing settings
  - Corrupt data
  - Different camera settings per pipeline
  - Camera not found

**10.4: Performance Tests**
- Test multiple pipelines on same camera
- Test stream aggregation performance
- Test frame sharing efficiency

---

## File Changes

### Files to CREATE:

1. `photon-core/src/main/java/org/photonvision/common/configuration/CameraSettings.java`
2. `photon-core/src/main/java/org/photonvision/vision/processes/CameraManager.java`
3. `photon-core/src/main/java/org/photonvision/vision/processes/Camera.java`
4. `photon-core/src/main/java/org/photonvision/vision/processes/GlobalPipelineManager.java`
5. `photon-core/src/main/java/org/photonvision/vision/processes/OutputStreamManager.java`
6. `photon-core/src/main/java/org/photonvision/common/configuration/ConfigMigrator.java`

### Files to MODIFY (Major Changes):

1. `photon-core/src/main/java/org/photonvision/vision/pipeline/CVPipelineSettings.java`
   - Add `sourceCameraUniqueName`
   - Deprecate camera-specific fields

2. `photon-core/src/main/java/org/photonvision/common/configuration/CameraConfiguration.java`
   - Add `CameraSettings cameraSettings`

3. `photon-core/src/main/java/org/photonvision/vision/processes/VisionModule.java`
   - Refactor to work with separated Camera and Pipeline
   - Remove camera settings management from pipeline switching

4. `photon-core/src/main/java/org/photonvision/vision/processes/PipelineManager.java`
   - Remove camera dependency
   - Make camera-independent

5. `photon-core/src/main/java/org/photonvision/vision/processes/VisionSourceManager.java`
   - Integrate with new CameraManager

6. `photon-core/src/main/java/org/photonvision/vision/processes/VisionModuleChangeSubscriber.java`
   - Update event routing for separated settings

7. `photon-server/src/main/java/org/photonvision/server/RequestHandler.java`
   - Add/update endpoints for camera and pipeline settings

8. `photon-core/src/main/java/org/photonvision/common/dataflow/websocket/UICameraConfiguration.java`
   - Separate camera settings from pipeline settings

### Files to MODIFY (Minor Changes):

9. `photon-core/src/main/java/org/photonvision/vision/processes/VisionSourceSettables.java`
10. `photon-core/src/main/java/org/photonvision/vision/processes/VisionRunner.java`
11. `photon-core/src/main/java/org/photonvision/common/configuration/ConfigManager.java`
12. `photon-core/src/main/java/org/photonvision/common/dataflow/websocket/UIDataPublisher.java`
13. `photon-core/src/main/java/org/photonvision/common/dataflow/networktables/NTDataPublisher.java`

### Frontend Files to MODIFY:

14. `photon-client/src/stores/settings/CameraSettingsStore.ts`
15. `photon-client/src/types/SettingTypes.ts`
16. `photon-client/src/types/PipelineTypes.ts`
17. Multiple Vue components in `photon-client/src/components/`

---

## Risks and Mitigation

### High-Risk Areas

**1. Configuration Migration**
- **Risk**: Data loss during migration from old format
- **Mitigation**:
  - Create automatic backups before migration
  - Extensive testing with real-world configs
  - Fallback to previous version if migration fails
  - Version flag to prevent re-migration

**2. Breaking Changes for Users**
- **Risk**: Existing configs incompatible with new version
- **Mitigation**:
  - Automatic migration on startup
  - Clear migration documentation
  - Support for config export/import
  - Beta testing period

**3. Stream Port Conflicts**
- **Risk**: Port allocation changes with new architecture
- **Mitigation**:
  - Keep existing port allocation logic
  - Ensure stream indices remain stable during migration
  - Document any changes to port allocation

**4. NetworkTables Compatibility**
- **Risk**: Breaking changes to NT structure visible to robot code
- **Mitigation**:
  - Maintain NT API compatibility
  - Only change internal structure, not published data
  - Test with real robot code

**5. Multiple Pipelines on Same Camera**
- **Risk**: Performance degradation, frame conflicts
- **Mitigation**:
  - Implement smart frame sharing (copy frame for each pipeline)
  - Add performance monitoring
  - Document performance implications
  - Consider frame caching strategies

### Medium-Risk Areas

**6. UI/UX Changes**
- **Risk**: Users confused by new UI organization
- **Mitigation**:
  - Clear documentation
  - Intuitive UI design
  - Beta testing period
  - Migration guide with screenshots

**7. Calibration Workflow**
- **Risk**: Calibration breaking with new structure
- **Mitigation**:
  - Maintain calibration as camera-level operation
  - Extensive testing of calibration flow
  - Ensure calibration data migrates correctly

---

## Migration Strategy

### User Migration Scenarios

**Scenario 1: Single Camera, Multiple Pipelines**
```
BEFORE:
Camera1 → [Pipeline1, Pipeline2, Pipeline3]
Each pipeline has potentially different camera settings

AFTER:
Camera1 (unified settings from Pipeline1)
Pipeline1 → sourceCameraUniqueName: Camera1
Pipeline2 → sourceCameraUniqueName: Camera1
Pipeline3 → sourceCameraUniqueName: Camera1

Migration:
- Use Pipeline1's camera settings as baseline
- User may need to adjust if other pipelines had different settings
```

**Scenario 2: Multiple Cameras**
```
BEFORE:
Camera1 → [Pipe1, Pipe2]
Camera2 → [Pipe3, Pipe4]

AFTER:
Camera1 (settings from Pipe1)
Camera2 (settings from Pipe3)
Pipe1, Pipe2 → sourceCameraUniqueName: Camera1
Pipe3, Pipe4 → sourceCameraUniqueName: Camera2
```

**Scenario 3: Pipeline Duplication (NEW FEATURE)**
```
User wants to run same pipeline on two cameras:
1. Duplicate camera configuration → Camera1_copy
2. Assign pipeline → sourceCameraUniqueName: Camera1_copy
3. Adjust camera-specific settings independently

This allows same processing logic on multiple cameras
with different exposure/brightness/etc.
```

---

## Implementation Checklist

### Backend Development

#### Phase 1: Data Structures
- [ ] Create CameraSettings class
- [ ] Update CVPipelineSettings with sourceCameraUniqueName
- [ ] Deprecate camera fields in CVPipelineSettings
- [ ] Update CameraConfiguration with CameraSettings

#### Phase 2: Camera Management
- [ ] Create Camera class
- [ ] Create CameraManager
- [ ] Implement camera settings application
- [ ] Implement camera stream management

#### Phase 3: Pipeline Management
- [ ] Create GlobalPipelineManager
- [ ] Refactor PipelineManager (camera-independent)
- [ ] Implement pipeline-camera association

#### Phase 4: Integration
- [ ] Refactor VisionModule
- [ ] Refactor VisionRunner
- [ ] Create OutputStreamManager
- [ ] Implement stream aggregation

#### Phase 5: API & Communication
- [ ] Update VisionSourceManager
- [ ] Update VisionModuleChangeSubscriber
- [ ] Update RequestHandler API endpoints
- [ ] Update UICameraConfiguration
- [ ] Update UIDataPublisher
- [ ] Update NTDataPublisher

#### Phase 6: Migration
- [ ] Create ConfigMigrator
- [ ] Update ConfigManager with migration logic
- [ ] Implement backup/rollback functionality
- [ ] Add version checking

### Frontend Development

- [ ] Update TypeScript types (SettingTypes.ts, PipelineTypes.ts)
- [ ] Update CameraSettingsStore
- [ ] Create/update PipelineSettingsStore
- [ ] Update camera settings components
- [ ] Update pipeline settings components
- [ ] Add camera selector to pipeline config
- [ ] Update dashboard view
- [ ] Update websocket message handling

### Testing

#### Unit Tests
- [ ] CameraSettings serialization/deserialization
- [ ] Camera class functionality
- [ ] CameraManager operations
- [ ] GlobalPipelineManager operations
- [ ] Pipeline-camera association

#### Integration Tests
- [ ] Camera switching between pipelines
- [ ] Multiple pipelines on same camera
- [ ] Stream aggregation
- [ ] Calibration workflow
- [ ] Settings persistence

#### Migration Tests
- [ ] Single camera config migration
- [ ] Multi camera config migration
- [ ] Edge cases (missing data, corrupt configs)
- [ ] Backwards compatibility
- [ ] Rollback functionality

#### Performance Tests
- [ ] Multiple pipelines on one camera
- [ ] Stream aggregation overhead
- [ ] Frame sharing efficiency

### Documentation

- [ ] Update architecture documentation
- [ ] Create migration guide for users
- [ ] Update API documentation
- [ ] Update user manual
- [ ] Create troubleshooting guide
- [ ] Document breaking changes

---

## Implementation Timeline Considerations

### Recommended Order

1. **Weeks 1-2**: Phase 1 (Data Structures)
   - Low risk, foundational work
   - Can be merged early

2. **Weeks 3-4**: Phase 2-3 (Camera & Pipeline Management)
   - Core architecture changes
   - Requires careful design

3. **Weeks 5-6**: Phase 4 (Integration)
   - Connect new components
   - Integration testing

4. **Weeks 7-8**: Phase 5-6 (API & Migration)
   - Backend complete
   - Migration tested

5. **Weeks 9-10**: Phase 7 (Frontend)
   - UI updates
   - End-to-end testing

6. **Weeks 11-12**: Testing & Polish
   - Comprehensive testing
   - Bug fixes
   - Documentation

### Parallel Work Opportunities

- Frontend can start once backend API is defined (after Phase 5)
- Migration can be developed alongside Phases 6-7
- Testing should be ongoing throughout
- Documentation can be written in parallel with development

---

## Critical Success Factors

1. **Preserve Data**: Zero data loss during migration
2. **Maintain Compatibility**: NT structure stays the same
3. **Performance**: No degradation with single pipeline per camera
4. **User Experience**: Clear UI, smooth migration
5. **Testing**: Comprehensive coverage before release
6. **Documentation**: Clear guides for users and developers

---

## Next Steps

1. Review this plan with team
2. Validate architectural decisions
3. Set up feature branch
4. Begin Phase 1 implementation
5. Create detailed task tracking (GitHub issues/project board)
6. Schedule regular architecture reviews
7. Plan beta testing period

---

## Questions to Resolve

1. Should VisionModule be kept as orchestrator or eliminated entirely?
2. How to handle conflicting camera settings from different pipelines in migration?
3. Should frame sharing use copy or reference semantics?
4. What's the limit on pipelines per camera (performance)?
5. How to handle camera disconnection with active pipelines?
6. Should calibration remain camera-level or move to pipeline-level?
7. How to handle backward compatibility for 3rd party integrations?

---

## Appendix: Key Architecture Diagrams

### Current Architecture (Simplified)
```
VisionSourceManager
└── VisionModule (per camera)
    ├── VisionSource (camera)
    ├── PipelineManager
    │   └── CVPipelineSettings (with camera settings)
    └── Streams (input/output)
```

### Target Architecture (Simplified)
```
VisionSourceManager
├── CameraManager
│   └── Camera instances
│       ├── VisionSource
│       ├── CameraSettings
│       └── Input streams
│
├── GlobalPipelineManager
│   └── Pipeline instances
│       └── CVPipelineSettings (with sourceCameraUniqueName)
│
└── VisionModule (orchestrator)
    └── OutputStreamManager (aggregates per camera)
```

---

## Document History

- **Version 1.0** - Initial plan created
- **Date**: 2025-12-27
- **Status**: Draft for Review
