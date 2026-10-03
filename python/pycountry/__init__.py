"""pycountry stand-in (T5.5): ISO 3166-1 countries and the ISO 639 languages Streamlink's l10n looks up, from a
small table generated with the real pycountry (tools/pycountry_tables.py) - the real package is ~6 MB of data."""
import json as _json
import os as _os
import pkgutil as _pkgutil

__version__ = "vidchain-stub"


class _Record:
    def __init__(self, d):
        self.__dict__.update(d)
        self._fields = d

    def __getattr__(self, name):
        raise AttributeError(name)

    def __repr__(self):
        return "%s(%s)" % (type(self).__name__, ", ".join("%s=%r" % kv for kv in self._fields.items()))

    def __eq__(self, other):
        return isinstance(other, _Record) and other._fields == self._fields

    def __hash__(self):
        return hash(tuple(sorted(self._fields.items())))


class Country(_Record):
    pass


class Language(_Record):
    pass


class _Database:
    def __init__(self, records, keys):
        self.objects = records
        self._keys = keys

    def __iter__(self):
        return iter(self.objects)

    def __len__(self):
        return len(self.objects)

    def get(self, default=None, **kw):
        if len(kw) != 1:
            raise TypeError("Only one criteria may be given")
        (field, value), = kw.items()
        if not isinstance(value, str):
            raise LookupError(value)
        v = value.lower()
        for r in self.objects:
            if str(getattr(r, field, "")).lower() == v:
                return r
        return default

    def lookup(self, value):
        if not isinstance(value, str):
            raise LookupError(value)
        v = value.lower()
        for k in self._keys:
            for r in self.objects:
                if str(getattr(r, k, "")).lower() == v:
                    return r
        raise LookupError(value)


def _load():
    raw = _pkgutil.get_data(__name__, "iso.json")
    return _json.loads(raw.decode("utf-8"))


_data = _load()
countries = _Database([Country(c) for c in _data["countries"]], ("alpha_2", "alpha_3", "numeric", "name", "official_name", "common_name"))
languages = _Database([Language(l) for l in _data["languages"]], ("alpha_2", "alpha_3", "bibliographic", "name"))
