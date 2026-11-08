// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.pptx

import io.github.Prestgg.naroffice.pptx.PptxMasterTemplate.DECL
import io.github.Prestgg.naroffice.pptx.PptxMasterTemplate.GRP
import io.github.Prestgg.naroffice.pptx.PptxMasterTemplate.NS

/** Parts of a blank 16:9 presentation with Title Slide, Title and Content and Blank layouts. */
internal object PptxTemplate {
    private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val CT = "application/vnd.openxmlformats-officedocument.presentationml"

    private fun rels(vararg r: Triple<String, String, String>) = DECL + "<Relationships xmlns=\"$REL_NS\">" +
        r.joinToString("") { "<Relationship Id=\"${it.first}\" Type=\"${it.second}\" Target=\"${it.third}\"/>" } +
        "</Relationships>"

    /** A placeholder that inherits everything; [spPr] may carry a frame. */
    private fun ph(id: Int, name: String, ph: String, spPr: String = "<p:spPr/>", body: String = "<a:bodyPr/>", lst: String = "<a:lstStyle/>", text: String = "") =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph $ph/></p:nvPr></p:nvSpPr>$spPr<p:txBody>$body$lst<a:p>""" +
            (if (text.isNotEmpty()) """<a:r><a:rPr lang="en-US"/><a:t>$text</a:t></a:r>""" else "") +
            """<a:endParaRPr lang="az-Latn-AZ"/></a:p></p:txBody></p:sp>"""

    private fun xfrm(x: Long, y: Long, cx: Long, cy: Long) =
        """<p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm></p:spPr>"""

    private fun layout(type: String, name: String, shapes: String) =
        DECL + """<p:sldLayout $NS type="$type" preserve="1"><p:cSld name="$name"><p:spTree>$GRP$shapes</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""

    private val layoutTitle = layout(
        "title", "Title Slide",
        ph(2, "Title 1", "type=\"ctrTitle\"", xfrm(1524000, 1122363, 9144000, 2387600), "<a:bodyPr anchor=\"b\"/>",
            """<a:lstStyle><a:lvl1pPr algn="ctr"><a:defRPr sz="6000"/></a:lvl1pPr></a:lstStyle>""", "Click to edit Master title style") +
            ph(3, "Subtitle 2", "type=\"subTitle\" idx=\"1\"", xfrm(1524000, 3602038, 9144000, 1655762), "<a:bodyPr/>",
                """<a:lstStyle><a:lvl1pPr marL="0" indent="0" algn="ctr"><a:buNone/><a:defRPr sz="2400"/></a:lvl1pPr></a:lstStyle>""", "Click to edit Master subtitle style"),
    )

    private val layoutContent = layout(
        "obj", "Title and Content",
        ph(2, "Title 1", "type=\"title\"", text = "Click to edit Master title style") +
            ph(3, "Content Placeholder 2", "idx=\"1\"", text = "Click to edit Master text styles"),
    )

    private val layoutBlank = layout("blank", "Blank", "")

    private val slide1 = DECL + """<p:sld $NS><p:cSld><p:spTree>$GRP""" +
        ph(2, "Title 1", "type=\"ctrTitle\"") + ph(3, "Subtitle 2", "type=\"subTitle\" idx=\"1\"") +
        """</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""

    private val presentation = DECL + """<p:presentation $NS saveSubsetFonts="1"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/><p:notesSz cx="6858000" cy="9144000"/><p:defaultTextStyle><a:defPPr><a:defRPr lang="en-US"/></a:defPPr><a:lvl1pPr marL="0" algn="l" defTabSz="914400" rtl="0" eaLnBrk="1" latinLnBrk="0" hangingPunct="1"><a:defRPr sz="1800" kern="1200"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mn-lt"/><a:ea typeface="+mn-ea"/><a:cs typeface="+mn-cs"/></a:defRPr></a:lvl1pPr></p:defaultTextStyle></p:presentation>"""

    private fun ov(part: String, type: String) = "<Override PartName=\"/$part\" ContentType=\"$type\"/>"

    private val contentTypes = DECL + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Default Extension="jpg" ContentType="image/jpeg"/><Default Extension="jpeg" ContentType="image/jpeg"/>""" +
        ov("ppt/presentation.xml", "$CT.presentation.main+xml") +
        ov("ppt/slideMasters/slideMaster1.xml", "$CT.slideMaster+xml") +
        ov("ppt/slideLayouts/slideLayout1.xml", "$CT.slideLayout+xml") +
        ov("ppt/slideLayouts/slideLayout2.xml", "$CT.slideLayout+xml") +
        ov("ppt/slideLayouts/slideLayout3.xml", "$CT.slideLayout+xml") +
        ov("ppt/slides/slide1.xml", "$CT.slide+xml") +
        ov("ppt/theme/theme1.xml", "application/vnd.openxmlformats-officedocument.theme+xml") +
        ov("ppt/presProps.xml", "$CT.presProps+xml") +
        ov("ppt/viewProps.xml", "$CT.viewProps+xml") +
        ov("ppt/tableStyles.xml", "$CT.tableStyles+xml") +
        ov("docProps/core.xml", "application/vnd.openxmlformats-package.core-properties+xml") +
        ov("docProps/app.xml", "application/vnd.openxmlformats-officedocument.extended-properties+xml") +
        "</Types>"

    fun parts(): Map<String, String> {
        val layoutRel = Triple("rId1", "$R/slideMaster", "../slideMasters/slideMaster1.xml")
        return linkedMapOf(
            "[Content_Types].xml" to contentTypes,
            "_rels/.rels" to rels(
                Triple("rId1", "$R/officeDocument", "ppt/presentation.xml"),
                Triple("rId2", "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties", "docProps/core.xml"),
                Triple("rId3", "$R/extended-properties", "docProps/app.xml"),
            ),
            "docProps/core.xml" to DECL + """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:creator>NarOffice</dc:creator></cp:coreProperties>""",
            "docProps/app.xml" to DECL + """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>NarOffice</Application></Properties>""",
            "ppt/presentation.xml" to presentation,
            "ppt/_rels/presentation.xml.rels" to rels(
                Triple("rId1", "$R/slideMaster", "slideMasters/slideMaster1.xml"),
                Triple("rId2", "$R/slide", "slides/slide1.xml"),
                Triple("rId3", "$R/theme", "theme/theme1.xml"),
                Triple("rId4", "$R/presProps", "presProps.xml"),
                Triple("rId5", "$R/viewProps", "viewProps.xml"),
                Triple("rId6", "$R/tableStyles", "tableStyles.xml"),
            ),
            "ppt/presProps.xml" to DECL + "<p:presentationPr $NS/>",
            "ppt/viewProps.xml" to DECL + """<p:viewPr $NS><p:gridSpacing cx="76200" cy="76200"/></p:viewPr>""",
            "ppt/tableStyles.xml" to DECL + """<a:tblStyleLst xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" def="{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}"/>""",
            "ppt/theme/theme1.xml" to PptxMasterTemplate.theme,
            "ppt/slideMasters/slideMaster1.xml" to PptxMasterTemplate.master,
            "ppt/slideMasters/_rels/slideMaster1.xml.rels" to rels(
                Triple("rId1", "$R/slideLayout", "../slideLayouts/slideLayout1.xml"),
                Triple("rId2", "$R/slideLayout", "../slideLayouts/slideLayout2.xml"),
                Triple("rId3", "$R/slideLayout", "../slideLayouts/slideLayout3.xml"),
                Triple("rId4", "$R/theme", "../theme/theme1.xml"),
            ),
            "ppt/slideLayouts/slideLayout1.xml" to layoutTitle,
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to rels(layoutRel),
            "ppt/slideLayouts/slideLayout2.xml" to layoutContent,
            "ppt/slideLayouts/_rels/slideLayout2.xml.rels" to rels(layoutRel),
            "ppt/slideLayouts/slideLayout3.xml" to layoutBlank,
            "ppt/slideLayouts/_rels/slideLayout3.xml.rels" to rels(layoutRel),
            "ppt/slides/slide1.xml" to slide1,
            "ppt/slides/_rels/slide1.xml.rels" to rels(Triple("rId1", "$R/slideLayout", "../slideLayouts/slideLayout1.xml")),
        )
    }
}
