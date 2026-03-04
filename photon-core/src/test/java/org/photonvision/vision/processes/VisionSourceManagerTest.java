/*
 * Copyright (C) Photon Vision.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.photonvision.vision.processes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.cscore.UsbCameraInfo;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.photonvision.common.LoadJNI;
import org.photonvision.common.configuration.CameraConfiguration;
import org.photonvision.common.configuration.ConfigManager;
import org.photonvision.common.util.TestUtils;
import org.photonvision.common.util.file.JacksonUtils;
import org.photonvision.vision.camera.PVCameraInfo;

public class VisionSourceManagerTest {
    // Test harness that overrides getConnectedCameras, but uses USB cameras for
    // everything else
    // when we start testing libcamera stuff we'll need to mock more stuff out
    private static class TestVsm extends VisionSourceManager {
        public List<PVCameraInfo> testCameras = new ArrayList<>();

        @Override
        protected List<PVCameraInfo> getConnectedCameras() {
            return testCameras;
        }

        public void teardown() {
            // release native resources
            var uniqueNames = getVisionModules().stream().map(VisionModule::uniqueName).toList();
            for (var name : uniqueNames) {
                deactivateVisionSource(name);
            }
        }
    }

    @BeforeAll
    public static void loadLibraries() {
        assertTrue(LoadJNI.loadLibraries());

        // Broadcast all still calls into configmanager (ew) so set that up here
        ConfigManager.getInstance().load();
    }

    private TestVsm vsm = null;

    @BeforeEach
    public void createVsm() {
        ConfigManager.getInstance().clearConfig();
        vsm = new TestVsm();
    }

    @AfterEach
    public void teardownVsm() {
        vsm.teardown();
    }

    @Test
    public void testCameraInfoSerde() throws InterruptedException, IOException {
        {
            var usb =
                    PVCameraInfo.fromUsbCameraInfo(
                            new UsbCameraInfo(
                                    2,
                                    "/dev/video2",
                                    "Left Camera", // renamed arducam
                                    new String[] {
                                        "/dev/v4l/by-id/usb-Arducam_Technology_Co.__Ltd._Left_Camera_12345-video-index0",
                                        "/dev/v4l/by-path/platform-xhci-hcd.0-usb-0:2:1.0-video-index0"
                                    },
                                    7,
                                    8));

            var str = JacksonUtils.serializeToString(usb);
            System.out.println(str);
            System.out.println(JacksonUtils.deserialize(str, PVCameraInfo.class));
        }
        {
            var csi =
                    PVCameraInfo.fromCSICameraInfo(
                            "/dev/v4l/by-path/platform-1f00110000.csi-video-index0", "rp1-cfe");
            var str = JacksonUtils.serializeToString(csi);
            System.out.println(str);
            System.out.println(JacksonUtils.deserialize(str, PVCameraInfo.class));
        }
    }

    @Test
    public void testEmpty() {
        var vsm = new TestVsm();

        List<CameraConfiguration> configs = List.of();
        vsm.registerLoadedConfigs(configs);

        // And make assertions about the current matching state
        assertEquals(0, vsm.getVsmState().allConnectedCameras.size());
        assertEquals(0, vsm.getVsmState().disabledConfigs.size());
        assertEquals(0, vsm.vmm.getModules().size());
    }

    @Test
    public void testFileVisionSource() throws InterruptedException, IOException {
        var fileCamera1 =
                PVCameraInfo.fromFileInfo(
                        TestUtils.getApriltagImagePath(TestUtils.ApriltagTestImages.kTag1_640_480, false)
                                .toAbsolutePath()
                                .toString(),
                        "kTag1_640_480");

        vsm.testCameras = List.of(fileCamera1);

        List<CameraConfiguration> configs = List.of();
        vsm.registerLoadedConfigs(configs);

        vsm.assignUnmatchedCamera(fileCamera1);

        System.out.println(JacksonUtils.serializeToString(ConfigManager.getInstance().getConfig()));

        // And make assertions about the current matching state
        assertEquals(1, vsm.getVsmState().allConnectedCameras.size());
        assertEquals(0, vsm.getVsmState().disabledConfigs.size());
        assertEquals(1, vsm.vmm.getModules().size());
    }

    @Test
    public void testEnabledDisabled() throws InterruptedException {
        // GIVEN a VSM
        var vsm = new TestVsm();
        // AND one enabled camera, and one disabled camera
        var enabledCam =
                new CameraConfiguration(
                        PVCameraInfo.fromUsbCameraInfo(
                                new UsbCameraInfo(
                                        0,
                                        "/dev/video0",
                                        "Lifecam HD-3000",
                                        new String[] {"/dev/v4l/by-path/foobar1"},
                                        5940,
                                        5940)));
        enabledCam.deactivated = false;
        enabledCam.nickname = "Matt's awesome camera 1";

        var disabledCam =
                new CameraConfiguration(
                        PVCameraInfo.fromUsbCameraInfo(
                                new UsbCameraInfo(
                                        1,
                                        "/dev/video1",
                                        "Lifecam HD-3000",
                                        new String[] {"/dev/v4l/by-path/foobar2"},
                                        5940,
                                        5940)));
        enabledCam.deactivated = true;
        enabledCam.nickname = "Matt's awesome camera 2";

        vsm.testCameras = List.of(enabledCam.matchedCameraInfo, disabledCam.matchedCameraInfo);

        // WHEN cameras are loaded from disk
        vsm.registerLoadedConfigs(List.of(enabledCam, disabledCam));

        // the enabled and disabled cameras will be matched
        assertEquals(2, vsm.getVsmState().allConnectedCameras.size());
        assertEquals(1, vsm.getVsmState().disabledConfigs.size());
        assertEquals(1, vsm.vmm.getModules().size());

        Thread.sleep(2000);

        vsm.teardown();
    }

    @Test
    public void testOtherPathsOrderChange() throws InterruptedException {
        // GIVEN a VSM
        var vsm = new TestVsm();
        // AND one camera and camera config with flipped otherpaths order
        var cam =
                PVCameraInfo.fromUsbCameraInfo(
                        new UsbCameraInfo(
                                0,
                                "/dev/video0",
                                "Lifecam HD-3000",
                                new String[] {"/dev/v4l/by-path/usbv2/foobar1", "/dev/v4l/by-path/usb/foobar1"},
                                5940,
                                5940));

        var camOtherPaths =
                PVCameraInfo.fromUsbCameraInfo(
                        new UsbCameraInfo(
                                1,
                                "/dev/video1",
                                "Lifecam HD-3000",
                                new String[] {"/dev/v4l/by-path/usb/foobar1", "/dev/v4l/by-path/usbv2/foobar1"},
                                5940,
                                5940));
        CameraConfiguration camOtherPathsConf = new CameraConfiguration(camOtherPaths);
        camOtherPathsConf.nickname = "TestCamera";
        camOtherPathsConf.deactivated = false;

        vsm.registerLoadedConfigs(List.of(camOtherPathsConf));

        vsm.assignUnmatchedCamera(cam);

        assertEquals(0, vsm.getVsmState().disabledConfigs.size());
        assertEquals(1, vsm.vmm.getModules().size());
        assertEquals(cam.uniquePath(), camOtherPaths.uniquePath());

        Thread.sleep(2000);

        vsm.teardown();
    }

    @Test
    public void testDuplicate() throws InterruptedException, IOException {
        var fileCamera1 =
                PVCameraInfo.fromFileInfo(
                        TestUtils.getApriltagImagePath(TestUtils.ApriltagTestImages.kTag1_640_480, false)
                                .toAbsolutePath()
                                .toString(),
                        "kTag1_640_480");
        CameraConfiguration camConf1 = new CameraConfiguration(fileCamera1);
        camConf1.deactivated = true;

        var fileCamera2 =
                PVCameraInfo.fromFileInfo(
                        TestUtils.getApriltagImagePath(TestUtils.ApriltagTestImages.kRobots, false)
                                .toAbsolutePath()
                                .toString(),
                        "kTag1_640_480");
        CameraConfiguration camConf2 = new CameraConfiguration(fileCamera2);
        camConf2.nickname = camConf1.nickname + " (1)";
        camConf2.uniqueName += "owo";
        camConf2.deactivated = true;

        var fileCamera3 =
                PVCameraInfo.fromFileInfo(
                        TestUtils.getApriltagImagePath(TestUtils.ApriltagTestImages.kTag1_640_480, false)
                                .toAbsolutePath()
                                .toString(),
                        "kTag1_640_480");

        vsm.testCameras = List.of(fileCamera1, fileCamera2, fileCamera3);

        List<CameraConfiguration> configs = List.of(camConf1, camConf2);
        vsm.registerLoadedConfigs(configs);

        vsm.assignUnmatchedCamera(fileCamera3);

        System.out.println(JacksonUtils.serializeToString(ConfigManager.getInstance().getConfig()));

        // And make assertions about the current matching state
        assertEquals(3, vsm.getVsmState().allConnectedCameras.size());
        assertEquals(2, vsm.getVsmState().disabledConfigs.size());
        assertEquals(1, vsm.vmm.getModules().size());
    }

    @Test
    public void testMismatch() throws InterruptedException {
        var vsm = new TestVsm();

        // Create a saved camera configuration that expects a device at /dev/video0 with a name
        PVCameraInfo savedInfo =
                PVCameraInfo.fromUsbCameraInfo(
                        new UsbCameraInfo(
                                0, "/dev/video0", "CamA", new String[] {"/dev/v4l/by-path/1"}, 111, 222));
        CameraConfiguration savedConf = new CameraConfiguration(savedInfo);
        savedConf.deactivated = false;
        savedConf.nickname = "SavedCam";

        // Register the saved config so VSM creates a VisionModule
        vsm.registerLoadedConfigs(List.of(savedConf));

        // Now simulate a connected camera at same uniquePath but with a different name (mismatch)
        List<PVCameraInfo> currentInfo =
                List.of(
                        PVCameraInfo.fromUsbCameraInfo(
                                new UsbCameraInfo(
                                        0,
                                        "/dev/video0",
                                        "CamDifferent",
                                        new String[] {"/dev/v4l/by-path/1"},
                                        111,
                                        222)));

        // Trigger state evaluation
        vsm.checkMismatches(currentInfo);

        // The module should have detected a mismatch
        assertTrue(vsm.getVisionModules().stream().anyMatch(m -> m.mismatch));

        // Now simulate the device being disconnected
        currentInfo = List.of();
        vsm.checkMismatches(currentInfo);

        // Mismatch should be cleared when device is disconnected
        assertFalse(vsm.getVisionModules().stream().anyMatch(m -> m.mismatch));

        // Test with a matching camera info
        currentInfo = List.of(savedInfo);
        vsm.checkMismatches(currentInfo);

        // The mismatch should be cleared
        assertFalse(vsm.getVisionModules().stream().anyMatch(m -> m.mismatch));

        vsm.teardown();
    }

    // --- macOS camera deduplication tests ---

    @Test
    public void testMacOSIdenticalCamerasOldCscoreGetDistinctUniquePaths() {
        // Simulate old macOS cscore output: empty otherPaths, vendorId/productId = -1
        // Two identical cameras with same AVFoundation uniqueID (collision scenario)
        var cam1 =
                PVCameraInfo.fromUsbCameraInfo(
                        new UsbCameraInfo(0, "0x1234abcd0000", "USB Camera", new String[] {}, -1, -1));
        var cam2 =
                PVCameraInfo.fromUsbCameraInfo(
                        new UsbCameraInfo(1, "0x1234abcd0000", "USB Camera", new String[] {}, -1, -1));

        // With old cscore (no VID/PID, no otherPaths), uniquePath falls back to
        // path + "::dev" + index, so they should be distinct
        assertNotEquals(cam1.uniquePath(), cam2.uniquePath());
        assertEquals("0x1234abcd0000::dev0", cam1.uniquePath());
        assertEquals("0x1234abcd0000::dev1", cam2.uniquePath());
    }

    @Test
    public void testMacOSIdenticalCamerasNewCscoreGetDistinctUniquePaths() {
        // Simulate new macOS cscore output: otherPaths populated with usb-location,
        // VID/PID populated. Two cameras at different USB ports.
        var cam1 =
                PVCameraInfo.fromUsbCameraInfo(
                        new UsbCameraInfo(
                                0,
                                "0x1234abcd0000",
                                "USB Camera",
                                new String[] {
                                    "modelID:UVC Camera VendorID_1234 ProductID_5678",
                                    "usb-location:0x14100000-vid1234-pid5678"
                                },
                                1234,
                                5678));
        var cam2 =
                PVCameraInfo.fromUsbCameraInfo(
                        new UsbCameraInfo(
                                1,
                                "0x5678efab0000",
                                "USB Camera",
                                new String[] {
                                    "modelID:UVC Camera VendorID_1234 ProductID_5678",
                                    "usb-location:0x14200000-vid1234-pid5678"
                                },
                                1234,
                                5678));

        // With new cscore, uniquePath uses usb-location: which encodes physical port
        assertNotEquals(cam1.uniquePath(), cam2.uniquePath());
        assertEquals("usb-location:0x14100000-vid1234-pid5678", cam1.uniquePath());
        assertEquals("usb-location:0x14200000-vid1234-pid5678", cam2.uniquePath());
    }

    @Test
    public void testMacOSCamerasDifferentPathsAreDistinct() {
        // Simulate macOS cameras with different AVFoundation uniqueIDs (different ports)
        // but old cscore (no VID/PID). These should be distinct even without the dev fix.
        var cam1 =
                PVCameraInfo.fromUsbCameraInfo(
                        new UsbCameraInfo(0, "0x14100000_unique1", "USB Camera", new String[] {}, -1, -1));
        var cam2 =
                PVCameraInfo.fromUsbCameraInfo(
                        new UsbCameraInfo(1, "0x14200000_unique2", "USB Camera", new String[] {}, -1, -1));

        // Different paths => different uniquePaths even with old cscore
        assertNotEquals(cam1.uniquePath(), cam2.uniquePath());
    }

    @Test
    public void testLinuxUniquePathUnchanged() {
        // Verify Linux behavior is unaffected: /by-path/ entry should be used
        var cam =
                PVCameraInfo.fromUsbCameraInfo(
                        new UsbCameraInfo(
                                0,
                                "/dev/video0",
                                "Lifecam HD-3000",
                                new String[] {
                                    "/dev/v4l/by-id/usb-Microsoft_LifeCam-video-index0",
                                    "/dev/v4l/by-path/platform-xhci-hcd.0-usb-0:1.3:1.0-video-index0"
                                },
                                5940,
                                5940));

        assertEquals(
                "/dev/v4l/by-path/platform-xhci-hcd.0-usb-0:1.3:1.0-video-index0", cam.uniquePath());
    }

    @Test
    public void testWindowsUniquePathUnchanged() {
        // Verify Windows behavior: path is used when otherPaths is empty but VID/PID > 0
        var cam =
                PVCameraInfo.fromUsbCameraInfo(
                        new UsbCameraInfo(
                                0,
                                "\\\\?\\usb#vid_045e&pid_0779#12345",
                                "Lifecam HD-3000",
                                new String[] {},
                                0x045e,
                                0x0779));

        // VID/PID > 0 so macOS fallback doesn't trigger, just returns path()
        assertEquals("\\\\?\\usb#vid_045e&pid_0779#12345", cam.uniquePath());
    }

    @Test
    public void testAssignDuplicateRawPathBlocked() throws InterruptedException, IOException {
        // Test that assigning a camera with the same raw path as an already-active
        // camera is blocked (even if uniquePaths differ due to dev index)

        // Use file cameras since we can't create real USB cameras in tests
        var fileCamera1 =
                PVCameraInfo.fromFileInfo(
                        TestUtils.getApriltagImagePath(TestUtils.ApriltagTestImages.kTag1_640_480, false)
                                .toAbsolutePath()
                                .toString(),
                        "DuplicateTest");

        vsm.testCameras = List.of(fileCamera1);
        vsm.registerLoadedConfigs(List.of());

        // First assignment should succeed
        assertTrue(vsm.assignUnmatchedCamera(fileCamera1));
        assertEquals(1, vsm.vmm.getModules().size());

        // Create a second camera with the same path (simulating duplication)
        var fileCamera2 =
                PVCameraInfo.fromFileInfo(
                        TestUtils.getApriltagImagePath(TestUtils.ApriltagTestImages.kTag1_640_480, false)
                                .toAbsolutePath()
                                .toString(),
                        "DuplicateTest");

        // Second assignment should be blocked (same raw path)
        assertFalse(vsm.assignUnmatchedCamera(fileCamera2));
        assertEquals(1, vsm.vmm.getModules().size());
    }

    @Test
    public void testReactivateDuplicatePathBlocked() throws InterruptedException {
        // Test that reactivating a camera is blocked when its path matches
        // an already-active camera

        var fileCamera1 =
                PVCameraInfo.fromFileInfo(
                        TestUtils.getApriltagImagePath(TestUtils.ApriltagTestImages.kTag1_640_480, false)
                                .toAbsolutePath()
                                .toString(),
                        "Camera1");
        var fileCamera2 =
                PVCameraInfo.fromFileInfo(
                        TestUtils.getApriltagImagePath(TestUtils.ApriltagTestImages.kTag1_640_480, false)
                                .toAbsolutePath()
                                .toString(),
                        "Camera1");

        // Set up: one active, one disabled (same path)
        CameraConfiguration conf1 = new CameraConfiguration(fileCamera1);
        conf1.deactivated = false;
        conf1.nickname = "Active";

        CameraConfiguration conf2 = new CameraConfiguration(fileCamera2);
        conf2.deactivated = true;
        conf2.nickname = "Disabled";
        conf2.uniqueName = conf1.uniqueName + "_dup";

        vsm.testCameras = List.of(fileCamera1, fileCamera2);
        vsm.registerLoadedConfigs(List.of(conf1, conf2));

        assertEquals(1, vsm.vmm.getModules().size());
        assertEquals(1, vsm.getVsmState().disabledConfigs.size());

        // Reactivating the disabled camera should fail (same raw path as active)
        assertFalse(vsm.reactivateDisabledCameraConfig(conf2.uniqueName));
        assertEquals(1, vsm.vmm.getModules().size());
    }
}
