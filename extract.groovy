#!/usr/bin/env groovy

/*
 * Usage:
 * ./extract.groovy <path-to-manifest/compose-file> <path-to-target-file> [<path-to-json-array-with-additional-images>]
 *
 */

@Grab('org.yaml:snakeyaml:1.28')
import org.yaml.snakeyaml.Yaml
import java.lang.ProcessBuilder
import java.util.regex.Pattern
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

String sanitizeFileName(String fileName) {
  // replace slash with underscore
  String sanitizedFileName = fileName.replaceAll('[/]', '_')

  // Remove invalid characters from the file name
  sanitizedFileName = sanitizedFileName.replaceAll('[\\\\/:*?"<>|]', '')

  // Remove leading and trailing whitespaces
  sanitizedFileName = sanitizedFileName.trim()

  // Replace multiple spaces with a single space
  sanitizedFileName = sanitizedFileName.replaceAll('\\s+', ' ')

  // Replace spaces with underscores
  sanitizedFileName = sanitizedFileName.replaceAll('\\s', '_')

  // Truncate the file name if it exceeds the maximum length
  int maxFileNameLength = 255 // Adjust as needed
  if (sanitizedFileName.length() > maxFileNameLength) {
      sanitizedFileName = sanitizedFileName.substring(0, maxFileNameLength)
  }

  return sanitizedFileName
}

def isComposeFile(String filePath) {
  def dockerCompose = new File(filePath).text
  def yaml = new Yaml()
  def parsedCompose = yaml.loadAll(dockerCompose) as List

  println "Yaml contains ${parsedCompose.size()} documents"

  if (parsedCompose.size() > 1) {
    return false
  }

  return !!parsedCompose[0].services && !!parsedCompose[0].version
}

def extractImageTagsFromDockerCompose(String filePath) {
  def dockerCompose = new File(filePath).text
  def yaml = new Yaml()
  def parsedCompose = yaml.load(dockerCompose)

  def imageTags = []
  parsedCompose.services.each { serviceName, serviceConfig ->
    if (serviceConfig.image) {
      def tag = serviceConfig.image
      imageTags.add(tag)
    }
  }

  return imageTags
}

def extractImageTagsFromKubernetesManifests(String filePath) {
  def manifests = new File(filePath).text
  def yaml = new Yaml()
  def parsedManifests = yaml.loadAll(manifests)

  def imageTags = []
  parsedManifests*.spec*.template*.spec*.containers*.image*.each {
    if (it) imageTags.add(it)
  }

  return imageTags
}

def boolean executeShellCommand(String command, def workingDirectory) {
  ProcessBuilder processBuilder = new ProcessBuilder(command.split())
  if (workingDirectory) {
    processBuilder.directory(workingDirectory as File)
  }
  Process process = processBuilder.start()

  // Read the command output
  def output = process.inputStream.text
  def errorOutput = process.errorStream.text

  // Wait for the command to complete
  int exitCode = process.waitFor()

  if (exitCode > 0) {
    println "Command failed: $command"
    println output
    println errorOutput
    return false
  }

  return true
}

// Check if the file path argument is provided
if (args.length < 2) {
  println("Please provide the path to the source and target files.")
  return
}

def filePath = args[0]
def file = new File(filePath)

// Check if the file exists
if (!file.exists()) {
  println("The specified file does not exist.")
  return
}

// Check if the file is a regular file
if (!file.isFile()) {
  println("The specified path does not point to a file.")
  return
}

def tags
if (isComposeFile(filePath)) {
  tags = extractImageTagsFromDockerCompose(filePath)
}
else {
  tags = extractImageTagsFromKubernetesManifests(filePath)
}

// remove duplicates
tags = tags.unique()

// add docker.io if applicable
/* XXX does not seem to be necessary
tags = tags.collect { tag ->
  if (tag.count('/') <= 1) {
    tag = 'docker.io/' + tag
  }
}
*/

println "Found ${tags.size} images:"

println JsonOutput.prettyPrint(JsonOutput.toJson(tags))

if (args.length > 2) {
  def extraPath = args[2]
  def extraFile = new File(extraPath)

  println "Checking file $extraPath for additional images..."

  if (extraFile.exists() && !extraFile.isDirectory()) {
    def json = new groovy.json.JsonSlurper().parse(extraFile)
    if (json instanceof List) {
      println "Found ${json.size()} images:"
      println JsonOutput.prettyPrint(JsonOutput.toJson(json))
      println "Merging with extracted images..."
      tags.addAll(json)
      tags = tags.unique()
      println "Total ${tags.size} images"
    }
    else {
      println "File is not a Json array, skipping"
    }
  }
  else {
    println "File not found, skipping"
  }
}

// sort images
tags = tags.sort()


// write target file
def targetPath = args[1]
def target = new File(targetPath)

target.text = JsonOutput.toJson(tags)
