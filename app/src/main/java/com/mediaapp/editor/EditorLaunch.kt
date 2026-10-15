package com.mediaapp.editor

import com.mediaapp.data.model.GalleryEntry
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EditorLaunchStore @Inject constructor() {
    var image: GalleryEntry? = null
    var video: GalleryEntry? = null
}
