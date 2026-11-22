import subprocess
import time
import xml.etree.ElementTree as ET
import os

ADB = "adb -s emulator-5554"

def run_cmd(cmd):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return p.stdout.strip()

def dump_ui():
    run_cmd(f"{ADB} shell uiautomator dump /sdcard/window_dump.xml")
    run_cmd(f"{ADB} pull /sdcard/window_dump.xml ./window_dump.xml")
    if os.path.exists("./window_dump.xml"):
        tree = ET.parse("./window_dump.xml")
        return tree.getroot()
    return None

def find_node(root, res_id=None, text=None):
    if root is None:
        return None
    for node in root.iter("node"):
        match = True
        if res_id and res_id not in node.attrib.get("resource-id", ""):
            match = False
        if text and text not in node.attrib.get("text", ""):
            match = False
        if match:
            return node
    return None

def get_node_center(node):
    bounds = node.attrib.get("bounds", "")  # [x1,y1][x2,y2]
    import re
    m = re.findall(r'\d+', bounds)
    if len(m) >= 4:
        x1, y1, x2, y2 = map(int, m[:4])
        return ((x1 + x2) // 2, (y1 + y2) // 2)
    return None

def tap_node(node):
    if node is None:
        return False
    c = get_node_center(node)
    if c:
        print(f"  [TAP] ({c[0]}, {c[1]}) for node {node.attrib.get('resource-id', '')}")
        run_cmd(f"{ADB} shell input tap {c[0]} {c[1]}")
        time.sleep(1.5)
        return True
    return False

def take_screenshot(filename):
    run_cmd(f"{ADB} shell screencap -p /sdcard/screen.png")
    run_cmd(f"{ADB} pull /sdcard/screen.png {filename}")
    print(f"  [SCREENSHOT] Saved: {filename}")

def unlock_if_needed():
    root = dump_ui()
    pin_node = find_node(root, text="Enter Administrator PIN")
    if pin_node is not None:
        print("[AUTH] PIN dialog detected. Entering 1234...")
        for n in root.iter("node"):
            if "EditText" in n.attrib.get("class", ""):
                tap_node(n)
                run_cmd(f"{ADB} shell input text 1234")
                time.sleep(0.5)
                break
        unlock_btn = find_node(dump_ui(), text="Unlock")
        if unlock_btn is not None:
            tap_node(unlock_btn)
            time.sleep(1)

    root = dump_ui()
    disclosure_btn = find_node(root, text="I Understand")
    if disclosure_btn is not None:
        print("[DISCLOSURE] Dismissing AI Disclosure...")
        tap_node(disclosure_btn)
        time.sleep(1)

def go_to_settings():
    unlock_if_needed()
    root = dump_ui()
    settings_tab = find_node(root, res_id="nav_tab_settings")
    if settings_tab is not None:
        print("[NAV] Tapping Settings tab...")
        tap_node(settings_tab)
        time.sleep(1)
    else:
        print("[NAV] Tapping Settings tab at coordinate (970, 2280)...")
        run_cmd(f"{ADB} shell input tap 970 2280")
        time.sleep(1)

def main():
    print("=== BEGIN KHATAWAS LANGUAGE SETTINGS STATE ADB VALIDATION ===")
    
    # Ensure app is running
    run_cmd(f"{ADB} shell am start -n com.khatawas/.MainActivity")
    time.sleep(2)
    unlock_if_needed()
    go_to_settings()
    
    root = dump_ui()
    rb_en = find_node(root, res_id="rb_lang_en")
    rb_hi = find_node(root, res_id="rb_lang_hi")
    rb_kn = find_node(root, res_id="rb_lang_kn")
    
    print("\n--- BASELINE CHECK (English Active) ---")
    if rb_en is not None:
        print(f"rb_lang_en: checked={rb_en.attrib.get('checked')} text='{rb_en.attrib.get('text')}'")
    if rb_hi is not None:
        print(f"rb_lang_hi: checked={rb_hi.attrib.get('checked')} text='{rb_hi.attrib.get('text')}'")
    if rb_kn is not None:
        print(f"rb_lang_kn: checked={rb_kn.attrib.get('checked')} text='{rb_kn.attrib.get('text')}'")
    take_screenshot("lang_01_baseline_english.png")
    
    # 1. Switch to Hindi
    print("\n--- TEST 1: SWITCH TO HINDI ---")
    root = dump_ui()
    rb_hi = find_node(root, res_id="rb_lang_hi")
    if rb_hi is not None:
        print("Tapping rb_lang_hi...")
        tap_node(rb_hi)
        time.sleep(2)
        unlock_if_needed()
        root = dump_ui()
        rb_en = find_node(root, res_id="rb_lang_en")
        rb_hi = find_node(root, res_id="rb_lang_hi")
        rb_kn = find_node(root, res_id="rb_lang_kn")
        print("After Hindi selection:")
        print(f"  rb_lang_en: checked={rb_en.attrib.get('checked') if rb_en is not None else 'N/A'}")
        print(f"  rb_lang_hi: checked={rb_hi.attrib.get('checked') if rb_hi is not None else 'N/A'} (MUST BE TRUE)")
        print(f"  rb_lang_kn: checked={rb_kn.attrib.get('checked') if rb_kn is not None else 'N/A'}")
        take_screenshot("lang_02_hindi_selected.png")
    else:
        print("ERROR: rb_lang_hi not found on screen")
    
    # 2. Switch to Kannada
    print("\n--- TEST 2: SWITCH TO KANNADA ---")
    root = dump_ui()
    rb_kn = find_node(root, res_id="rb_lang_kn")
    if rb_kn is not None:
        print("Tapping rb_lang_kn...")
        tap_node(rb_kn)
        time.sleep(2)
        unlock_if_needed()
        root = dump_ui()
        rb_en = find_node(root, res_id="rb_lang_en")
        rb_hi = find_node(root, res_id="rb_lang_hi")
        rb_kn = find_node(root, res_id="rb_lang_kn")
        print("After Kannada selection:")
        print(f"  rb_lang_en: checked={rb_en.attrib.get('checked') if rb_en is not None else 'N/A'}")
        print(f"  rb_lang_hi: checked={rb_hi.attrib.get('checked') if rb_hi is not None else 'N/A'}")
        print(f"  rb_lang_kn: checked={rb_kn.attrib.get('checked') if rb_kn is not None else 'N/A'} (MUST BE TRUE)")
        take_screenshot("lang_03_kannada_selected.png")
    else:
        print("ERROR: rb_lang_kn not found on screen")
    
    # 3. Switch back to English
    print("\n--- TEST 3: SWITCH TO ENGLISH ---")
    root = dump_ui()
    rb_en = find_node(root, res_id="rb_lang_en")
    if rb_en is not None:
        print("Tapping rb_lang_en...")
        tap_node(rb_en)
        time.sleep(2)
        unlock_if_needed()
        root = dump_ui()
        rb_en = find_node(root, res_id="rb_lang_en")
        rb_hi = find_node(root, res_id="rb_lang_hi")
        rb_kn = find_node(root, res_id="rb_lang_kn")
        print("After English selection:")
        print(f"  rb_lang_en: checked={rb_en.attrib.get('checked') if rb_en is not None else 'N/A'} (MUST BE TRUE)")
        print(f"  rb_lang_hi: checked={rb_hi.attrib.get('checked') if rb_hi is not None else 'N/A'}")
        print(f"  rb_lang_kn: checked={rb_kn.attrib.get('checked') if rb_kn is not None else 'N/A'}")
        take_screenshot("lang_04_english_restored.png")
    else:
        print("ERROR: rb_lang_en not found on screen")
    
    # 4. App Restart Persistence (select Kannada, force stop, reopen)
    print("\n--- TEST 4: PROCESS RESTART PERSISTENCE ---")
    root = dump_ui()
    rb_kn = find_node(root, res_id="rb_lang_kn")
    if rb_kn is not None:
        tap_node(rb_kn)
        time.sleep(2)
        unlock_if_needed()
    
    print("[RESTART] Force stopping com.khatawas...")
    run_cmd(f"{ADB} shell am force-stop com.khatawas")
    time.sleep(1)
    print("[RESTART] Relaunching com.khatawas...")
    run_cmd(f"{ADB} shell am start -n com.khatawas/.MainActivity")
    time.sleep(2)
    unlock_if_needed()
    go_to_settings()
    root = dump_ui()
    rb_kn = find_node(root, res_id="rb_lang_kn")
    print(f"After app restart, Kannada checked={rb_kn.attrib.get('checked') if rb_kn is not None else 'N/A'} (MUST BE TRUE)")
    take_screenshot("lang_05_restart_persistence_kannada.png")
    
    # 5. Tab Navigation Persistence (switch to Hindi, browse Home->Customers->Settings)
    print("\n--- TEST 5: TAB NAVIGATION PERSISTENCE ---")
    root = dump_ui()
    rb_hi = find_node(root, res_id="rb_lang_hi")
    if rb_hi is not None:
        tap_node(rb_hi)
        time.sleep(2)
        unlock_if_needed()
    
    print("[NAV] Navigating to Home tab...")
    run_cmd(f"{ADB} shell input tap 108 2280")
    time.sleep(1)
    print("[NAV] Navigating to Customers tab...")
    run_cmd(f"{ADB} shell input tap 324 2280")
    time.sleep(1)
    print("[NAV] Navigating back to Settings tab...")
    run_cmd(f"{ADB} shell input tap 970 2280")
    time.sleep(1)
    root = dump_ui()
    rb_hi = find_node(root, res_id="rb_lang_hi")
    print(f"After tab navigation, Hindi checked={rb_hi.attrib.get('checked') if rb_hi is not None else 'N/A'} (MUST BE TRUE)")
    take_screenshot("lang_06_navigation_persistence_hindi.png")
    
    # 6. Rapid Repeated Selection
    print("\n--- TEST 6: RAPID REPEATED SELECTION ---")
    for lang_id in ["rb_lang_en", "rb_lang_kn", "rb_lang_hi", "rb_lang_kn"]:
        root = dump_ui()
        btn = find_node(root, res_id=lang_id)
        if btn is not None:
            tap_node(btn)
            time.sleep(0.5)
    
    time.sleep(2)
    unlock_if_needed()
    root = dump_ui()
    rb_kn = find_node(root, res_id="rb_lang_kn")
    print(f"After rapid selection, final selection (Kannada) checked={rb_kn.attrib.get('checked') if rb_kn is not None else 'N/A'}")
    take_screenshot("lang_07_rapid_selection_stable.png")

    print("\n=== VALIDATION COMPLETE ===")

if __name__ == "__main__":
    main()
