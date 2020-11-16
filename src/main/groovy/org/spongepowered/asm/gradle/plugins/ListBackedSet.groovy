package org.spongepowered.asm.gradle.plugins

import org.gradle.api.internal.file.collections.MinimalFileSet

import java.util.ArrayList
import java.util.Collection
import java.util.HashSet
import java.util.Iterator
import java.util.List
import java.util.Set

class ListBackedSet<E> implements MinimalFileSet {
    List<E> innerList

    ListBackedSet(Collection<E> elements) {
        if (elements != null) {
            innerList = new ArrayList<E>(elements)
        } else {
            innerList = new ArrayList<E>()
        }
    }

    void add(E e) {
        innerList.add(e)
    }

    int size() {
        toSet().size()
    }

    Iterator<E> iterator() {
        toSet().iterator()
    }

    void remove(E e) {
        innerList.remove(e)
    }

    boolean contains(E e) {
        innerList.contains(e)
    }

    Set<E> toSet() {
        new HashSet<E>(innerList)
    }

    @Override
    Set<File> getFiles() {
        toSet()
    }

    @Override
    String getDisplayName() {
        "list backed file collection"
    }
}
