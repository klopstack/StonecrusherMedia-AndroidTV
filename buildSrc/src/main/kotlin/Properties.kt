import org.gradle.api.Project

/**
 * Helper function to retrieve configuration variable values
 */
fun Project.getProperty(name: String): String? {
	// sample.var --> SAMPLE_VAR
	val environmentName = name.uppercase().replace(".", "_")

	return findProperty(name)?.toString() ?: System.getenv(environmentName) ?: null
}

fun Project.getBooleanProperty(name: String, default: Boolean = false): Boolean =
	getProperty(name)?.toBooleanStrictOrNull() ?: default

const val EMBY_ENABLED_PROPERTY = "moonfin.emby.enabled"
