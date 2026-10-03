"""lxml stand-in for Streamlink on the bundled Python (T5.5, Q21 = A): the native lxml cannot be loaded there, so
lxml.etree is provided on top of xml.etree (parsing) and elementpath (XPath). Only what Streamlink uses."""
__version__ = "5.4.0-vidchain-stub"
