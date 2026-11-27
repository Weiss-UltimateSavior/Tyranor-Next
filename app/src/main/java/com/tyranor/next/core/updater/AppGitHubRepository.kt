package com.tyranor.next.core.updater

internal object AppGitHubRepository {
    private const val REPOSITORY = "SIRENA-Core/Tyranor-Next"

    const val PROJECT_URL = "https://github.com/$REPOSITORY"
    const val RELEASES_URL = "$PROJECT_URL/releases"
    const val RELEASES_API = "https://api.github.com/repos/$REPOSITORY/releases"
}
