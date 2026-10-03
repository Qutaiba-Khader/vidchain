"""lxml.etree on xml.etree + elementpath (T5.5). Elements are xml.etree Elements with an xpath() method;
HTML() builds an <html><body>-wrapped tree with the standard html.parser like lxml's forgiving HTML parser."""
import xml.etree.ElementTree as _ET
from html.parser import HTMLParser as _HTMLParser

import elementpath as _ep
from elementpath.tree_builders import build_node_tree as _build_node_tree

LXML_VERSION = (5, 4, 0, 0)
__version__ = "5.4.0-vidchain-stub"


class Error(Exception):
    pass


class XPathError(Error):
    pass


class XPathEvalError(XPathError):
    pass


class XPathSyntaxError(XPathError, SyntaxError):
    pass


class LxmlError(Error):
    pass


class ParseError(LxmlError, SyntaxError):
    pass


class XMLSyntaxError(ParseError):
    pass


class ParserError(LxmlError):
    pass


class _Element(_ET.Element):
    def xpath(self, _path, namespaces=None, extensions=None, smart_strings=True, **variables):
        # elementpath treats anything with an .xpath attribute as lxml: build its plain ElementTree node tree from the
        # document's top element instead and evaluate with this element as the context item (lxml semantics: "//x"
        # searches the whole document, "./x" starts here)
        top = getattr(self, "_vc_root", self)
        doc = _build_node_tree(_ET.ElementTree(top))
        item = next((n for n in doc.iter_descendants() if getattr(n, "value", None) is self), None)
        try:
            parser = _parser_with(extensions, self, namespaces)
            token = parser.parse(_path)
            res = token.evaluate(_ep.XPathContext(doc, item=item, variables=variables or None))
        except _ep.ElementPathError as e:
            # lxml's own wording, which callers (Streamlink's validators) show
            msg = str(e)
            raise XPathEvalError("Unregistered function" if "XPST0017" in msg else "Undefined variable" if "XPST0008" in msg else "Invalid expression") from None
        return _results(res, smart_strings)

    def getroottree(self):
        return _ElementTree(self)

    # lxml raises SyntaxError("invalid path") for a broken ElementPath; xml.etree may fail with TypeError/KeyError
    def find(self, path, namespaces=None):
        return _path_call(super().find, path, namespaces)

    def findall(self, path, namespaces=None):
        return _path_call(super().findall, path, namespaces)

    def findtext(self, path, default=None, namespaces=None):
        try:
            return super().findtext(path, default, namespaces)
        except (TypeError, KeyError, StopIteration):
            raise SyntaxError("invalid path") from None

    def iterfind(self, path, namespaces=None):
        return iter(_path_call(super().findall, path, namespaces))

    def iterchildren(self, tag=None):
        return (c for c in self if tag is None or c.tag == tag)


def _path_call(fn, path, namespaces):
    try:
        return fn(path, namespaces)
    except (TypeError, KeyError, StopIteration):
        raise SyntaxError("invalid path") from None


class _Ctx:
    """what lxml hands an extension function as its first argument"""

    def __init__(self, node):
        self.context_node = node


def _parser_with(extensions, element, namespaces):
    if not extensions:
        return _ep.XPath1Parser(namespaces=namespaces)
    # elementpath registers external functions only on XPath 2.0+ parsers; a private copy keeps them per call
    cls = type("_VidChainXPath2Parser", (_ep.XPath2Parser,), {"symbol_table": dict(_ep.XPath2Parser.symbol_table)})
    p = cls(namespaces=namespaces)
    for (ns, name), fn in extensions.items():
        p.external_function(lambda *a, _fn=fn: _fn(_Ctx(element), *a), name=name)
    return p


class _SmartString(str):
    """lxml's string result of text() / @attr: a str that knows where it came from"""

    def __new__(cls, value, parent, is_text=False, is_tail=False, is_attribute=False, attrname=None):
        s = super().__new__(cls, value)
        s._parent, s.is_text, s.is_tail, s.is_attribute, s.attrname = parent, is_text, is_tail, is_attribute, attrname
        return s

    def getparent(self):
        return self._parent


def _results(res, smart):
    if isinstance(res, bool):
        return res
    if isinstance(res, (int, float)):
        return float(res)
    if isinstance(res, str):
        return res
    out = []
    for n in (res if isinstance(res, list) else [res]):
        kind = type(n).__name__
        value = getattr(n, "value", n)
        if "Attribute" in kind:
            out.append(_SmartString(value, n.parent.value, is_attribute=True, attrname=n.name) if smart else str(value))
        elif kind == "TextNode":
            parent = n.parent.value
            if parent.text == value:
                out.append(_SmartString(value, parent, is_text=True) if smart else str(value))
            else:
                owner = next((c for c in parent if c.tail == value), parent)
                out.append(_SmartString(value, owner, is_tail=True) if smart else str(value))
        elif isinstance(value, (str, bytes)):
            out.append(str(value))
        else:
            out.append(value)
    return out


class _ElementTree(_ET.ElementTree):
    def xpath(self, _path, **kw):
        return self.getroot().xpath(_path, **kw)


_Attrib = dict
_Comment = _ET.Element
ElementTree = _ElementTree


def iselement(element):
    return isinstance(element, _ET.Element)


def Element(_tag, attrib=None, nsmap=None, **extra):
    a = dict(attrib or {})
    a.update(extra)
    return _Element(_tag, a)


def SubElement(_parent, _tag, attrib=None, nsmap=None, **extra):
    e = Element(_tag, attrib, nsmap, **extra)
    _parent.append(e)
    return e


class XMLParser:
    def __init__(self, *args, **kwargs):
        self.options = kwargs


class HTMLParser(XMLParser):
    pass


_CHARSET = __import__("re").compile(rb"""<meta[^>]+charset\s*=\s*["']?\s*([A-Za-z0-9_.:-]+)|<\?xml[^>]+encoding\s*=\s*["']([A-Za-z0-9_.:-]+)""", __import__("re").I)


def _text(data):
    if isinstance(data, (bytes, bytearray)):
        data = bytes(data)
        if data.startswith(b"\xef\xbb\xbf"):
            return data[3:].decode("utf-8", "replace")
        m = _CHARSET.search(data[:4096])
        enc = (m.group(1) or m.group(2)).decode("ascii") if m else "iso-8859-1"     # libxml2's HTML default
        try:
            return data.decode(enc, "replace")
        except LookupError:
            return data.decode("iso-8859-1")
    return data


def XML(text, parser=None, base_url=None):
    if not isinstance(text, (str, bytes, bytearray)):
        raise ValueError("can only parse strings")
    if isinstance(text, str) and text.lstrip()[:5] == "<?xml" and "encoding=" in text.split("?>", 1)[0]:
        raise ValueError("Unicode strings with encoding declaration are not supported. Please use bytes input or XML fragments without declaration.")
    tb = _ET.TreeBuilder(element_factory=_Element)
    p = _ET.XMLParser(target=tb)
    try:
        p.feed(text)
        return _rooted(p.close())
    except _ET.ParseError as e:
        raise XMLSyntaxError(str(e)) from None


def _rooted(root):
    for e in root.iter():
        e._vc_root = root
    return root


fromstring = XML


class iterparse:
    """lxml.etree.iterparse on xml.etree's pull parser, building VidChain elements; .root once parsed"""

    def __init__(self, source, events=("end",), tag=None, **kwargs):
        self._close = False
        if isinstance(source, (str, bytes)) and not hasattr(source, "read"):
            source, self._close = open(source, "rb"), True
        self._source, self._tag, self.root = source, tag, None
        self._target = _ET.TreeBuilder(element_factory=_Element)
        self._pull = _ET.XMLPullParser(events=events, _parser=_ET.XMLParser(target=self._target))
        self._it = self._run()

    def __iter__(self):
        return self

    def __next__(self):
        return next(self._it)

    def _matches(self, el):
        return self._tag is None or getattr(el, "tag", None) == self._tag

    def _run(self):
        try:
            while True:
                chunk = self._source.read(64 * 1024)
                if not chunk:
                    break
                self._pull.feed(chunk)
                for ev, el in self._pull.read_events():
                    if self.root is None and isinstance(el, _ET.Element):
                        self.root = el if ev == "start" else self.root
                    if self._matches(el):
                        yield ev, el
            root = self._pull._close_and_return_root()
            for ev, el in self._pull.read_events():
                if self._matches(el):
                    yield ev, el
            self.root = _rooted(root)
        except _ET.ParseError as e:
            raise XMLSyntaxError(str(e)) from None
        finally:
            if self._close:
                self._source.close()


_VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr", "keygen", "command"}
_HEAD = {"title", "meta", "link", "style", "script", "base", "noscript"}


class _Builder(_HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.root = _Element("html", {})
        self.head = None
        self.body = None
        self.stack = [self.root]

    def _container(self, tag):
        if tag == "html":
            return None
        if tag == "head" or (self.body is None and tag in _HEAD and len(self.stack) == 1):
            if self.head is None:
                self.head = SubElement(self.root, "head")
            return self.head
        if self.body is None:
            self.body = SubElement(self.root, "body")
        return self.body

    def handle_starttag(self, tag, attrs):
        attrib = {k: (v if v is not None else "") for k, v in attrs}
        if tag == "html":
            self.root.attrib.update(attrib)
            return
        if tag in ("head", "body"):
            el = self.head if tag == "head" else self.body
            if el is None:
                el = SubElement(self.root, tag)
                if tag == "head":
                    self.head = el
                else:
                    self.body = el
            el.attrib.update(attrib)
            self.stack = [self.root, el]
            return
        parent = self.stack[-1] if len(self.stack) > 1 else self._container(tag)
        el = SubElement(parent, tag, attrib)
        if tag not in _VOID:
            self.stack.append(el)

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in _VOID and len(self.stack) > 1 and self.stack[-1].tag == tag:
            self.stack.pop()

    def handle_endtag(self, tag):
        for i in range(len(self.stack) - 1, 0, -1):
            if self.stack[i].tag == tag:
                del self.stack[i:]
                return

    def handle_data(self, data):
        if not data:
            return
        if len(self.stack) == 1:
            if not data.strip():
                return
            parent = self._container("p")
            self.stack = [self.root, parent]
        cur = self.stack[-1]
        if len(cur):
            last = cur[-1]
            last.tail = (last.tail or "") + data
        else:
            cur.text = (cur.text or "") + data


def HTML(text, parser=None, base_url=None):
    if not isinstance(text, (str, bytes, bytearray)):
        raise ValueError("can only parse strings")
    text = _text(text)
    if not text or not text.strip():
        raise ParserError("Document is empty")
    b = _Builder()
    b.feed(text)
    b.close()
    return _rooted(b.root)


def tostring(element_or_tree, encoding=None, method="xml", xml_declaration=None, pretty_print=False, with_tail=True, **kw):
    el = element_or_tree.getroot() if isinstance(element_or_tree, _ET.ElementTree) else element_or_tree
    m = "html" if method == "html" else "xml"
    if encoding in (str, "unicode"):
        out = _ET.tostring(el, encoding="unicode", method=m)
        return out.replace(" />", "/>") if m == "xml" else out
    out = _ET.tostring(el, encoding=encoding or "us-ascii", method=m)
    return out.replace(b" />", b"/>") if m == "xml" else out
