package com.autoglm.android.shizuku;

import android.os.ParcelFileDescriptor;

interface IDisplayService {
    void destroy() = 16777114;
    
    // Create a virtual display and start streaming
    // Returns display ID, or -1 on failure
    int createDisplay(int width, int height, int density, String name) = 1;
    
    // Get the video stream file descriptor for a display
    ParcelFileDescriptor getDisplayStream(int displayId) = 2;
    
    // Destroy a virtual display
    void destroyDisplay(int displayId) = 3;
    
    // Inject touch event to display
    boolean injectTouch(int displayId, int action, float x, float y) = 4;
    
    // Get list of active display IDs
    int[] getActiveDisplayIds() = 5;
    
    // Start app on display
    boolean startAppOnDisplay(int displayId, String packageName) = 6;
}
