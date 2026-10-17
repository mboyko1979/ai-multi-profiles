package dev.boyko.aiagentswitcher

import com.intellij.openapi.util.IconLoader

/** Original vector artwork; IconLoader selects the matching dark-theme variant. */
object MultiIcons {
    @JvmField val Agent = IconLoader.getIcon("/icons/aiMulti.svg", MultiIcons::class.java)
    @JvmField val Profile = IconLoader.getIcon("/icons/profile.svg", MultiIcons::class.java)
    @JvmField val Separator = IconLoader.getIcon("/icons/profileSeparator.svg", MultiIcons::class.java)
}
