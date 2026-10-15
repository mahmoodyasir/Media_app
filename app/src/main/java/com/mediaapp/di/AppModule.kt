package com.mediaapp.di

import com.mediaapp.data.source.SourceClassifier
import com.mediaapp.editor.image.ImageEditor
import com.mediaapp.editor.image.UnavailableImageEditor
import com.mediaapp.editor.video.UnavailableVideoEditor
import com.mediaapp.editor.video.VideoEditor
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ClassifierModule {
    @Provides
    @Singleton
    fun sourceClassifier(): SourceClassifier = SourceClassifier()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EditorModule {
    @Binds
    @Singleton
    abstract fun videoEditor(impl: UnavailableVideoEditor): VideoEditor

    @Binds
    @Singleton
    abstract fun imageEditor(impl: UnavailableImageEditor): ImageEditor
}
