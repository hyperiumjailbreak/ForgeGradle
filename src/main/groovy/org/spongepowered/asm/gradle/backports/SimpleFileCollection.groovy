/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.spongepowered.asm.gradle.backports

import org.gradle.api.internal.file.collections.FileCollectionAdapter
import org.gradle.api.internal.file.collections.ListBackedFileSet
import org.gradle.api.internal.file.collections.MinimalFileSet

import java.io.File
import java.io.Serializable
import java.util.Collection

/**
 * https://github.com/gradle/gradle/issues/6274
 * We use this API, and don't want to migrate off it, so here it is, without deprecation warnings.
 */
class SimpleFileCollection extends FileCollectionAdapter implements Serializable {
    SimpleFileCollection(File... files) {
        this(new ListBackedFileSet(files))
    }

    SimpleFileCollection(Collection<File> files) {
        this(new ListBackedFileSet(files))
    }

    private SimpleFileCollection(MinimalFileSet fileSet) {
        super(fileSet)
    }
}
