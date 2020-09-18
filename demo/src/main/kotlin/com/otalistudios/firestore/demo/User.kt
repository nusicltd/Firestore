@file:Suppress("unused")

package com.otalistudios.firestore.demo

import com.google.firebase.firestore.Exclude
import com.otaliastudios.firestore.FirestoreClass
import com.otaliastudios.firestore.FirestoreDocument
import com.otaliastudios.firestore.FirestoreList
import com.otaliastudios.firestore.FirestoreMap

@FirestoreClass(addUpdatedAt = false)
class User : FirestoreDocument() {
    var type: Int by this
    var imageUrl: String? by this("image_url")
    var messages: Messages by this
    var ignoredByLackOfPropertyDelegate: Boolean = true
    // FIXME: This is currently not working. KotlinPoet issue?
    @get:Exclude var ignoredByAnnotation: Boolean by this

    @FirestoreClass
    class Messages : FirestoreList<Message>()

    @FirestoreClass
    class Message : FirestoreMap<Any?>() {
        var from: String by this
        var to: String by this
        var text: String? by this()
    }

    init {
        // Default values
        type = 1
    }
}