#!/usr/bin/env python3
"""Writes python/pycountry/iso.json from the real pycountry: ISO 3166-1 countries and all ISO 639-3 languages
(Streamlink's l10n looks up alpha_2, alpha_3, bibliographic codes and names). usage: pycountry_tables.py > iso.json"""
import json

import pycountry

countries = [{k: getattr(c, k) for k in ("alpha_2", "alpha_3", "name", "numeric", "official_name", "common_name") if hasattr(c, k)} for c in pycountry.countries]
languages = [{k: getattr(l, k) for k in ("alpha_2", "alpha_3", "name", "bibliographic") if hasattr(l, k)} for l in pycountry.languages]
print(json.dumps({"source": "pycountry " + getattr(pycountry, "__version__", "?"), "countries": countries, "languages": languages}, ensure_ascii=False, separators=(",", ":")))
