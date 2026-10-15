package com.mediaapp.editor.image

import com.mediaapp.editor.EditResult
import com.mediaapp.editor.ImageEditOperation
import com.mediaapp.editor.ImageEditRequest
import javax.inject.Inject
import javax.inject.Singleton

interface ImageEditor {
    val operations: List<ImageEditOperation>
    suspend fun apply(request: ImageEditRequest): EditResult
}

@Singleton
class UnavailableImageEditor @Inject constructor() : ImageEditor {
    override val operations: List<ImageEditOperation> = ImageEditOperation.entries

    override suspend fun apply(request: ImageEditRequest): EditResult {
        return EditResult.Unavailable(
            "${request.operation.label} is part of the photo editor, planned for the next phase.",
        )
    }
}
