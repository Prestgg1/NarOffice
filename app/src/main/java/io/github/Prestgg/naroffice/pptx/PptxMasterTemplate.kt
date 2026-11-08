// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.pptx

/** Theme and slide master of the blank presentation. */
internal object PptxMasterTemplate {
    const val NS = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main""""
    const val DECL = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

    private fun accent(n: Int, hex: String) = "<a:accent$n><a:srgbClr val=\"$hex\"/></a:accent$n>"
    private fun ln(w: Int) = """<a:ln w="$w" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/><a:miter lim="800000"/></a:ln>"""
    private const val PH_SOLID = """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>"""

    val theme = DECL + """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="NarOffice"><a:themeElements><a:clrScheme name="NarOffice"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="3B2A2C"/></a:dk2><a:lt2><a:srgbClr val="F3ECEA"/></a:lt2>""" +
        accent(1, "B5202E") + accent(2, "E07A2F") + accent(3, "4F8A3C") + accent(4, "2F6DB5") + accent(5, "7A4FA0") + accent(6, "C9A227") +
        """<a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme><a:fontScheme name="NarOffice"><a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme><a:fmtScheme name="NarOffice"><a:fillStyleLst>""" +
        PH_SOLID + """<a:solidFill><a:schemeClr val="phClr"><a:tint val="50000"/></a:schemeClr></a:solidFill><a:solidFill><a:schemeClr val="phClr"><a:shade val="80000"/></a:schemeClr></a:solidFill></a:fillStyleLst><a:lnStyleLst>""" +
        ln(6350) + ln(12700) + ln(19050) +
        """</a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst>""" +
        PH_SOLID + PH_SOLID + PH_SOLID +
        """</a:bgFillStyleLst></a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"""

    const val GRP = """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>"""

    private fun ph(id: Int, name: String, ph: String, x: Long, y: Long, cx: Long, cy: Long, body: String, text: String) =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph $ph/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr><p:txBody>$body<a:lstStyle/><a:p><a:r><a:rPr lang="en-US"/><a:t>$text</a:t></a:r><a:endParaRPr lang="en-US"/></a:p></p:txBody></p:sp>"""

    private const val BODY_PR = """<a:bodyPr vert="horz" lIns="91440" tIns="45720" rIns="91440" bIns="45720" rtlCol="0">"""

    private fun bodyLvl(n: Int, marL: Long, sz: Int) =
        """<a:lvl${n}pPr marL="$marL" indent="-228600" algn="l" defTabSz="914400" rtl="0" eaLnBrk="1" latinLnBrk="0" hangingPunct="1"><a:lnSpc><a:spcPct val="90000"/></a:lnSpc><a:spcBef><a:spcPts val="${if (n == 1) 1000 else 500}"/></a:spcBef><a:buFont typeface="Arial"/><a:buChar char="•"/><a:defRPr sz="$sz" kern="1200"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mn-lt"/><a:ea typeface="+mn-ea"/><a:cs typeface="+mn-cs"/></a:defRPr></a:lvl${n}pPr>"""

    val master = DECL + """<p:sldMaster $NS><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg><p:spTree>$GRP""" +
        ph(2, "Title Placeholder 1", "type=\"title\"", 838200, 365125, 10515600, 1325563,
            "$BODY_PR anchor=\"ctr\"><a:normAutofit/></a:bodyPr>".replace("rtlCol=\"0\"> anchor", "rtlCol=\"0\" anchor"), "Click to edit Master title style") +
        ph(3, "Text Placeholder 2", "type=\"body\" idx=\"1\"", 838200, 1825625, 10515600, 4351338,
            "$BODY_PR<a:normAutofit/></a:bodyPr>", "Click to edit Master text styles") +
        ph(4, "Date Placeholder 3", "type=\"dt\" sz=\"half\" idx=\"2\"", 838200, 6356350, 2743200, 365125,
            "$BODY_PR</a:bodyPr>".replace("<a:bodyPr ", "<a:bodyPr anchor=\"ctr\" "), "") +
        ph(5, "Footer Placeholder 4", "type=\"ftr\" sz=\"quarter\" idx=\"3\"", 4038600, 6356350, 4114800, 365125,
            "$BODY_PR</a:bodyPr>".replace("<a:bodyPr ", "<a:bodyPr anchor=\"ctr\" "), "") +
        ph(6, "Slide Number Placeholder 5", "type=\"sldNum\" sz=\"quarter\" idx=\"4\"", 8610600, 6356350, 2743200, 365125,
            "$BODY_PR</a:bodyPr>".replace("<a:bodyPr ", "<a:bodyPr anchor=\"ctr\" "), "") +
        """</p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/><p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/><p:sldLayoutId id="2147483650" r:id="rId2"/><p:sldLayoutId id="2147483651" r:id="rId3"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle><a:lvl1pPr algn="l" defTabSz="914400" rtl="0" eaLnBrk="1" latinLnBrk="0" hangingPunct="1"><a:lnSpc><a:spcPct val="90000"/></a:lnSpc><a:spcBef><a:spcPct val="0"/></a:spcBef><a:buNone/><a:defRPr sz="4400" kern="1200"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mj-lt"/><a:ea typeface="+mj-ea"/><a:cs typeface="+mj-cs"/></a:defRPr></a:lvl1pPr></p:titleStyle><p:bodyStyle>""" +
        bodyLvl(1, 228600, 2800) + bodyLvl(2, 685800, 2400) + bodyLvl(3, 1143000, 2000) + bodyLvl(4, 1600200, 1800) + bodyLvl(5, 2057400, 1800) +
        """</p:bodyStyle><p:otherStyle><a:defPPr><a:defRPr lang="en-US"/></a:defPPr><a:lvl1pPr marL="0" algn="l" defTabSz="914400" rtl="0" eaLnBrk="1" latinLnBrk="0" hangingPunct="1"><a:defRPr sz="1800" kern="1200"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mn-lt"/><a:ea typeface="+mn-ea"/><a:cs typeface="+mn-cs"/></a:defRPr></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>"""
}
