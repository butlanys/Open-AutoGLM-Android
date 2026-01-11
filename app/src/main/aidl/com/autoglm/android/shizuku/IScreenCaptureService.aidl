/*
 * Copyright (C) 2024 AutoGLM
 *
 * Screen capture AIDL interface inspired by scrcpy project.
 * scrcpy is licensed under Apache License 2.0
 * Copyright (C) 2018 Genymobile
 * Copyright (C) 2018-2024 Romain Vimont
 * https://github.com/Genymobile/scrcpy
 */

package com.autoglm.android.shizuku;

import android.os.ParcelFileDescriptor;

parcelable ScreenCaptureResult;

interface IScreenCaptureService {
    void destroy() = 16777114;
    
    /**
     * Capture screenshot and return result via shared memory.
     * 
     * @param maxWidth Maximum width (0 for no limit)
     * @param maxHeight Maximum height (0 for no limit)
     * @param quality JPEG quality (1-100), 0 for PNG
     * @return ScreenCaptureResult containing image data and metadata
     */
    ScreenCaptureResult captureScreen(int maxWidth, int maxHeight, int quality) = 1;
    
    /**
     * Get display information.
     * 
     * @return Display info as "width,height,rotation,density"
     */
    String getDisplayInfo() = 2;
}
