#!/usr/bin/env groovy
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.control.SourceUnit

import java.nio.file.Files


Optional<File> f = (args.length)? Optional<File>.ofNullable(new File(args.first())).filter(f -> f.isFile()): Optional.empty()

if (f.isEmpty()) {
    println "usage: packager <script_file>"
    System.err.println("script_file must be a file")
    System.exit(1)
}

final File scriptFile = f.get()


def scriptFileName = scriptFile.name.toString()
def scriptName = (scriptFileName.contains(".")) ? scriptFileName.take(scriptFileName.lastIndexOf('.')) : scriptFileName
def scriptText = scriptFile.text

def deps = ["implementation('org.codehaus.groovy:groovy-all:3.0.9')"]


deps += extractDeps(scriptText).collect { "implementation('$it')" }
scriptText = scriptText.replaceAll(/(?m)^(\s)*@Grab[\s\S]*?\((.|\n)*?\)/, "")

def projectDirectory = Files.createTempDirectory("groovy-script-packager-").toFile()
def settingsFile = new File(projectDirectory, "settings.gradle")
def buildFile = new File(projectDirectory, "build.gradle")
def srcDir = new File(projectDirectory, "src")
def targetScriptFile = new File(srcDir, scriptFileName)

srcDir.mkdirs()
targetScriptFile.text = scriptText
settingsFile.text = """\
rootProject.name = "${scriptName}"
"""

buildFile.text = """\
plugins {
  id 'groovy'
  id 'application'
}
repositories { mavenCentral() }
dependencies {  ${deps.join("\n")} }
sourceSets.main.groovy.srcDirs = ['src']
mainClassName = '$scriptName'
sourceCompatibility = 11
targetCompatibility = 11
"""

def process = "gradle -p ${projectDirectory} distZip".execute()
StringBuilder sout = new StringBuilder(), serr = new StringBuilder()
process.consumeProcessOutput(sout, serr)
process.waitForOrKill(15000L)

if (sout) println "out:\n$sout"
if (serr) println "err:\n$serr"

def distZipFile = new File(projectDirectory, "build/distributions/${scriptName}.zip")
Files.copy(distZipFile.toPath(), new File(".", "${scriptName}.zip").toPath())

// Library fns
static def extractDeps(scriptText) {
    ModuleNode ast = SourceUnit.create("script", scriptText).with {
        parse()
        completePhase()
        convert()
        getAST()
    }

    List<ImportNode> importNodes = ast.with() {
        imports + starImports + staticImports.values() + staticStarImports.values()
    }

    ArrayList<AnnotationNode> grabAnnotations = importNodes.inject(new ArrayList<ImportNode>()) { acc, ImportNode importNode ->
        acc + importNode.annotations.findAll { it.classNode.name == "Grab" }
    }

    return grabAnnotations.collect { annotationNode ->
        def members = annotationNode.getMembers()
        if (members.containsKey('value')) {
            return getValue(annotationNode, "value")
        } else if (members.keySet().containsAll(['group', 'module', 'version'])) { //TODO support classifier
            return [getValue(annotationNode, "group"),
                    getValue(annotationNode, "module"),
                    getValue(annotationNode, "version")].join(":")
        } else return null
    }.findAll() // drop the nulls
}

private static String getValue(AnnotationNode annotationNode, String attr) {
    ((ConstantExpression) annotationNode.getMember(attr))?.value?.toString()
}
