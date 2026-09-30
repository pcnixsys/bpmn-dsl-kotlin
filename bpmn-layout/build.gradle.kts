plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":bpmn-model"))
}
