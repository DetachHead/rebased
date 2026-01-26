// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
@file:JvmName("UpdateData")
package com.intellij.openapi.updateSettings.impl

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.impl.ApplicationInfoImpl
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.intellij.lang.annotations.Language
import org.intellij.markdown.IElementType
import org.jdom.Element
import org.jdom.JDOMException
import org.jetbrains.annotations.ApiStatus.Internal
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor

@Throws(IOException::class, JDOMException::class)
fun parseUpdateData(
  text: String,
  productCode: String = ApplicationInfo.getInstance().build.productCode,
): Product? = parseUpdateData(JDOMUtil.load(text), productCode)

fun parseUpdateData(node: Element, productCode: String = ApplicationInfo.getInstance().build.productCode): Product? =
  node.getChildren("product")
    .find { it.getChildren("code").any { code -> code.value.trim() == productCode } }
    ?.let { Product(it, productCode) }

// TODO: this is mostly copied from com.intellij.markdown.utils.MarkdownToHtmlConverter, but importing it here causes a circular dependency
private fun convertMarkdownToHtml(markdownText: String): String {
  // https://github.com/JetBrains/markdown/issues/49
  // this isn't a windows-specific issue, the github api just returns \r\n in the release description
  val markdownTextLf = markdownText.replace("\r\n", "\n")
  val flavour = GFMFlavourDescriptor()
  // https://github.com/JetBrains/markdown/issues/72
  val parsedTree = MarkdownParser(flavour).parse(IElementType("ROOT"),markdownTextLf)
  return HtmlGenerator(markdownTextLf, parsedTree, flavour).generateHtml()
}

/**
 * gets update data from github releases instead of an updates.xml file
 */
fun parseUpdateData(
  json: JsonObject,
  productCode: String = ApplicationInfo.getInstance().build.productCode
): Product? {
  val channelType = UpdateSettings.getInstance().state.updateChannelType
  val newVersion = json["tag_name"]?.jsonPrimitive?.content
  val releaseDescription = json["body"]?.jsonPrimitive?.content
  check(newVersion != null && releaseDescription != null) {
    "failed to check for updates: newVersion=${newVersion}, releaseDescription=${releaseDescription}"
  }
  val githubUrl = "https://github.com/detachhead/rebased"
  val releasesUrl = "$githubUrl/releases/latest"
  // we convert the release info to the format from https://www.jetbrains.com/updates/updates.xml because it's easier to just do that
  // than to update all the classes that can only be constructed from the parsed xml
  @Language("XML")
  val xml = """
    <products>
      <product name="${ApplicationNamesInfo.getInstance().fullProductName}">
        <code>${ApplicationInfoImpl.getInstance().build.productCode}</code>
        <channel 
            name="$channelType"
            id="$channelType"
            status="$channelType"
            url="$releasesUrl"
            feedback="$githubUrl/issues"
            majorVersion="$newVersion"
            licensing="$channelType"
        >
            <build number="$newVersion" version="$newVersion" fullNumber="$newVersion">
                <message><![CDATA[${convertMarkdownToHtml(releaseDescription)}]]></message>
                <button name="Download" url="$releasesUrl" download="true"/>
            </build>
        </channel>
      </product>
    </products>
  """.trimIndent()
  return parseUpdateData(xml, productCode)
}

class Product internal constructor(node: Element, private val productCode: String) {
  val name: @NlsSafe String = node.getMandatoryAttributeValue("name")
  val channels: List<UpdateChannel> = node.getChildren("channel").map { UpdateChannel(it, productCode) }
  val disableMachineId: Boolean = node.getAttributeValue("disableMachineId", "false") == "true"

  override fun toString(): String = productCode
}

class UpdateChannel @Internal constructor(node: Element, productCode: String) {
  enum class Licensing { EAP, RELEASE; }

  val id: String = node.getMandatoryAttributeValue("id")
  val status: ChannelStatus = ChannelStatus.fromCode(node.getAttributeValue("status"))
  val licensing: Licensing = if (node.getAttributeValue("licensing") == "eap") Licensing.EAP else Licensing.RELEASE
  val evalDays: Int = node.getAttributeValue("evalDays")?.toInt() ?: 30
  val url: String? = node.getAttributeValue("url")
  val builds: List<BuildInfo> = node.getChildren("build").map { BuildInfo(it, productCode) }

  override fun toString(): String = id
}

class BuildInfo internal constructor(node: Element, productCode: String) {
  val number: BuildNumber = parseBuildNumber(node.getMandatoryAttributeValue("fullNumber", "number"), productCode)
  val apiVersion: BuildNumber = node.getAttributeValue("apiVersion")?.let { BuildNumber.fromStringWithProductCode(it, number.productCode) } ?: number
  val version: String = node.getAttributeValue("version") ?: ""
  val message: @NlsSafe String = node.getChild("message")?.value ?: ""
  val blogPost: String? = node.getChild("blogPost")?.getAttributeValue("url")
  val releaseDate: Date? = parseDate(node.getAttributeValue("releaseDate"))
  val target: BuildRange? = BuildRange.fromStrings(node.getAttributeValue("targetSince"), node.getAttributeValue("targetUntil"))
  val patches: List<PatchInfo> = node.getChildren("patch").map(::PatchInfo)
  val downloadUrl: String? = node.getChildren("button").find { it.getAttributeValue("download") != null }?.getMandatoryAttributeValue("url")

  private fun parseBuildNumber(value: String, productCode: String): BuildNumber {
    val buildNumber = BuildNumber.fromString(value)!!
    return if (buildNumber.productCode.isNotEmpty()) buildNumber else BuildNumber(productCode, *buildNumber.components)
  }

  private fun parseDate(value: String?): Date? =
    if (value == null) null
    else try {
      SimpleDateFormat("yyyyMMdd", Locale.US).parse(value)  // same as the 'majorReleaseDate' in ApplicationInfo.xml
    }
    catch (_: ParseException) {
      logger<BuildInfo>().info("invalid build release date: ${value}")
      null
    }

  override fun toString(): String = "${number}/${version}"
}

@Internal
class PatchInfo internal constructor(node: Element) {
  companion object {
    val OS_SUFFIX: String = if (SystemInfoRt.isWindows) "win" else if (SystemInfoRt.isMac) "mac" else if (SystemInfo.isUnix) "unix" else "unknown"
  }

  val fromBuild: BuildNumber = BuildNumber.fromString(node.getMandatoryAttributeValue("fullFrom", "from"))!!
  val size: String? = node.getAttributeValue("size")
  val isAvailable: Boolean = node.getAttributeValue("exclusions")?.splitToSequence(",")?.none { it.trim() == OS_SUFFIX } ?: true
}

private fun Element.getMandatoryAttributeValue(attribute: String) =
  getAttributeValue(attribute) ?: throw JDOMException("${name}@${attribute} missing")

private fun Element.getMandatoryAttributeValue(attribute: String, fallback: String) =
  getAttributeValue(attribute) ?: getMandatoryAttributeValue(fallback)
