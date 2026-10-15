package com.mediaapp.editor.video

import com.mediaapp.editor.EditResult
import com.mediaapp.editor.VideoEditOperation
import com.mediaapp.editor.VideoEditRequest
import javax.inject.Inject
import javax.inject.Singleton

interface VideoEditor {
    val operations: List<VideoEditOperation>
    suspend fun apply(request: VideoEditRequest): EditResult
}

@Singleton
class UnavailableVideoEditor @Inject constructor() : VideoEditor {
    override val operations: List<VideoEditOperation> = VideoEditOperation.entries

    override suspend fun apply(request: VideoEditRequest): EditResult {
        return EditResult.Unavailable(
            "${request.operation.label} is part of the video editor, planned for a later phase. " +
                "Playback, trim points, and overlays will run through Media3 Transformer without leaving Media App.",
        )
    }
}
