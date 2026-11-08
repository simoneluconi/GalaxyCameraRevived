#!/usr/bin/env python3
"""Makes the gd1 overlay that has Settings call the device a camera, not a phone
(overlay/packages/apps/Settings/res/values*/phone_to_camera.xml).

Usage: settings-phone2camera.py <Settings>/res-product <lang suffix> <out xml>
(lang suffix '' for English, '-it' for Italian). Prints each change for review:
rerun it after a Settings rebase and check the Italian agreement by eye."""
import re
import sys

src_dir, lang, out = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(f"{src_dir}/values{lang}/strings.xml", encoding="utf-8").read()

# Whole <string> elements with product="default"
elems = re.findall(r'(\s*(?:<!--(?:(?!-->).)*-->\s*)?<string name="([^"]+)"[^>]*product="default"[^>]*>(.*?)</string>)',
                   text, re.S)

# Plain strings (no product variants) in res/ that the GC100 shows; features it lacks
# (fingerprint, face, NFC, satellite, watch unlock) are left alone
PLAIN = {"zen_mode_restrict_notifications_mute_footer", "zen_mode_restrict_notifications_hide_footer",
         "condition_zen_summary_phone_muted", "zen_mode_automation_suggestion_title",
         "usb_default_info", "wifi_hidden_network_warning", "style_suggestion_title",
         "activity_not_found", "bluetooth_audio_routing_summary",
         "disabled_feature_reason_slow_down_phone"}
plain_text = open(f"{src_dir}/../res/values{lang}/strings.xml", encoding="utf-8").read()
elems += [e for e in re.findall(r'((<string name="([^"]+)"[^>]*>)(.*?)</string>)', plain_text, re.S)
          if e[2] in PLAIN and "product=" not in e[1]]
elems = [(e[0], e[2], e[3]) if len(e) == 4 else e for e in elems]

EN = [
    (r"\bphone number", "PHONE_NUMBER"),
    (r"\bPhone number", "PHONE_NUMBER_CAP"),
    (r"\bphones\b", "cameras"),
    (r"\bPhones\b", "Cameras"),
    (r"\bphone\b", "camera"),
    (r"\bPhone\b", "Camera"),
    (r"PHONE_NUMBER_CAP", "Phone number"),
    (r"PHONE_NUMBER", "phone number"),
]
IT = [
    (r"numero di telefono", "NUMTEL"),
    (r"Numero di telefono", "NUMTEL_CAP"),
    # Italian Settings also says smartphone
    (r"\b(sul|del|al)lo smartphone", lambda m: m[1] + "la fotocamera"),
    (r"\blo smartphone", "la fotocamera"),
    (r"\bLo smartphone", "La fotocamera"),
    (r"(\\n|\b)Il (tuo|suo) smartphone", lambda m: m[1] + "La " + m[2][:-1] + "a fotocamera"),
    (r"Altoparlanti smartphone", "Altoparlanti fotocamera"),
    (r"(\\n|\b)(I|i)l (tuo|suo) telefono", lambda m: m[1] + ("L" if m[2] == "I" else "l") + "a " + m[3][:-1] + "a fotocamera"),
    (r"\b(d|s|n|D|S|N)(el|ul|al) (tuo|suo) telefono",
     lambda m: m[1] + m[2] + "la " + m[3][:-1] + "a fotocamera"),
    (r"\b(al|Al) (tuo|suo) telefono", lambda m: m[1] + "la " + m[2][:-1] + "a fotocamera"),
    (r"\b(tuo|suo) telefono", lambda m: m[1][:-1] + "a fotocamera"),
    (r"\bIl telefono", "La fotocamera"),
    (r"\bil telefono", "la fotocamera"),
    (r"\b(d|s|n|D|S|N)(el|ul) telefono", lambda m: m[1] + m[2] + "la fotocamera"),
    (r"\b(a|A|d|D)(al|l) telefono", lambda m: m[1] + m[2] + "la fotocamera"),
    (r"\bquesto telefono", "questa fotocamera"),
    (r"\bQuesto telefono", "Questa fotocamera"),
    (r"\btelefono", "fotocamera"),
    (r"\bTelefono", "Fotocamera"),
    (r"NUMTEL_CAP", "Numero di telefono"),
    (r"NUMTEL", "numero di telefono"),
    # Fotocamera is feminine: participles and pronouns that referred to the phone
    (r"fotocamera verrà scollegato", "fotocamera verrà scollegata"),
    (r"fotocamera non sarà più accoppiato", "fotocamera non sarà più accoppiata"),
    (r"Fotocamera (non )?criptato", lambda m: "Fotocamera " + (m[1] or "") + "criptata"),
    (r"smarrito, rubato o reimpostato", "smarrita, rubata o reimpostata"),
    (r"smarrito o rubato", "smarrita o rubata"),
    (r"potresti sbloccarlo", "potresti sbloccarla"),
    (r"fotocamera (di tuo figlio )?può essere sbloccato", lambda m: "fotocamera " + (m[1] or "") + "può essere sbloccata"),
    (r"se viene rivolto verso", "se viene rivolta verso"),
    (r"qualcuno lo rivolge", "qualcuno la rivolge"),
    (r"qualcuno te lo avvicina", "qualcuno te la avvicina"),
    (r"fotocamera è sbloccato", "fotocamera è sbloccata"),
    (r"fotocamera quando lo tieni", "fotocamera quando la tieni"),
]
rules = IT if lang == "-it" else EN

# Strings about calls, phone support and the radio info page keep "phone"
KEEP = {"support_summary", "testing_phone_info", "reset_internet_text"}

entries = []
for whole, name, value in elems:
    if name in KEEP:
        continue
    new = value
    for pat, rep in rules:
        new = re.sub(pat, rep, new)
    if new != value:
        entries.append((name, new))
        print(f"{name}\n  - {value}\n  + {new}")

with open(out, "w", encoding="utf-8") as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n')
    f.write("<!-- Settings calls the device a camera, not a phone. Generated from\n"
            "     res-product's product=\"default\" strings; numbers and calls keep \"phone\". -->\n")
    f.write('<resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">\n')
    for name, new in entries:
        f.write(f'    <string name="{name}">{new}</string>\n')
    f.write("</resources>\n")
print(f"{len(entries)} of {len(elems)} strings changed", file=sys.stderr)
