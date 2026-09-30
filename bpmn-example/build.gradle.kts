plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":bpmn-model"))
    implementation(project(":bpmn-dsl"))
    implementation(project(":bpmn-validation"))
    implementation(project(":bpmn-xml"))
    implementation(project(":bpmn-layout"))
    implementation(project(":bpmn-flowable"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

application { mainClass.set("in.o612.eng.bpmn.MainKt") }
tasks.test { useJUnitPlatform() }

tasks.register<JavaExec>("renderVerify") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("in.o612.eng.bpmn.MainKt")
}
