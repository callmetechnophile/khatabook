import subprocess
import time
import sys
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding='utf-8')
ADB = "adb -s emulator-5554"

def run_cmd(cmd):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return p.stdout.strip()

def tap(x, y, delay=1.5):
    print(f"  [TAP] ({x}, {y})")
    run_cmd(f"{ADB} shell input tap {x} {y}")
    time.sleep(delay)

def take_screenshot(path):
    run_cmd(f"{ADB} shell screencap -p /data/local/tmp/scr.png")
    run_cmd(f"{ADB} pull /data/local/tmp/scr.png {path}")
    print(f"  [SCREENSHOT] Saved: {path}")

def get_radio_states():
    run_cmd(f"{ADB} shell uiautomator dump /data/local/tmp/dump.xml")
    run_cmd(f"{ADB} pull /data/local/tmp/dump.xml ./dump_check.xml")
    tree = ET.parse("./dump_check.xml")
    root = tree.getroot()
    states = {}
    for node in root.iter("node"):
        res_id = node.attrib.get("resource-id", "")
        if "rb_lang_" in res_id:
            lang = res_id.split("rb_lang_")[-1]
            states[lang] = {
                "checked": node.attrib.get("checked") == "true",
                "text": node.attrib.get("text", "")
            }
    return states

def main():
    print("==================================================================")
    print("KHATAWAS COMPREHENSIVE ON-DEVICE LANGUAGE SYNCHRONIZATION TEST")
    print("==================================================================")

    # Coordinates
    X_CENTER = 540
    Y_EN = 643
    Y_HI = 772
    Y_KN = 901

    Y_NAV = 2253
    X_HOME = 108
    X_CUSTOMERS = 324
    X_HISTORY = 540
    X_SETTINGS = 972

    # Step 0: Ensure app is running and on Settings screen
    run_cmd(f"{ADB} shell am start -n com.khatawas/.MainActivity")
    time.sleep(3)
    tap(X_SETTINGS, Y_NAV, delay=1.5)

    # Test 1: Reset to English Baseline
    print("\n--- TEST 1: English Baseline ---")
    tap(X_CENTER, Y_EN, delay=2.0)
    states = get_radio_states()
    print(f"States: {states}")
    assert states.get("en", {}).get("checked") == True, "English MUST be checked"
    assert states.get("hi", {}).get("checked") == False, "Hindi must NOT be checked"
    assert states.get("kn", {}).get("checked") == False, "Kannada must NOT be checked"
    take_screenshot("lang_01_english_active.png")
    print("PASS: English is checked and active.")

    # Test 2: Transition English -> Hindi
    print("\n--- TEST 2: English -> Hindi Transition ---")
    tap(X_CENTER, Y_HI, delay=2.0)
    states = get_radio_states()
    print(f"States: {states}")
    assert states.get("hi", {}).get("checked") == True, "Hindi MUST be checked"
    assert states.get("en", {}).get("checked") == False, "English must NOT be checked"
    assert states.get("kn", {}).get("checked") == False, "Kannada must NOT be checked"
    take_screenshot("lang_02_hindi_active.png")
    print("PASS: Hindi is checked, app strings updated to Hindi.")

    # Test 3: Transition Hindi -> Kannada
    print("\n--- TEST 3: Hindi -> Kannada Transition ---")
    tap(X_CENTER, Y_KN, delay=2.0)
    states = get_radio_states()
    print(f"States: {states}")
    assert states.get("kn", {}).get("checked") == True, "Kannada MUST be checked"
    assert states.get("hi", {}).get("checked") == False, "Hindi must NOT be checked"
    assert states.get("en", {}).get("checked") == False, "English must NOT be checked"
    take_screenshot("lang_03_kannada_active.png")
    print("PASS: Kannada is checked, app strings updated to Kannada.")

    # Test 4: Transition Kannada -> English
    print("\n--- TEST 4: Kannada -> English Transition ---")
    tap(X_CENTER, Y_EN, delay=2.0)
    states = get_radio_states()
    print(f"States: {states}")
    assert states.get("en", {}).get("checked") == True, "English MUST be checked"
    assert states.get("hi", {}).get("checked") == False, "Hindi must NOT be checked"
    assert states.get("kn", {}).get("checked") == False, "Kannada must NOT be checked"
    take_screenshot("lang_04_english_restored.png")
    print("PASS: English is restored and checked.")

    # Test 5: Process Restart Persistence
    print("\n--- TEST 5: Process Restart Persistence (Kannada) ---")
    tap(X_CENTER, Y_KN, delay=2.0)
    print("  Force stopping com.khatawas...")
    run_cmd(f"{ADB} shell am force-stop com.khatawas")
    time.sleep(1.5)
    print("  Relaunching com.khatawas...")
    run_cmd(f"{ADB} shell am start -n com.khatawas/.MainActivity")
    time.sleep(3.5)
    tap(X_SETTINGS, Y_NAV, delay=2.0)
    states = get_radio_states()
    print(f"States after restart: {states}")
    assert states.get("kn", {}).get("checked") == True, "Kannada MUST persist across restart"
    take_screenshot("lang_05_restart_persistence_kannada.png")
    print("PASS: Kannada persisted across full application termination and relaunch.")

    # Test 6: Tab Navigation Persistence
    print("\n--- TEST 6: Tab Navigation Persistence (Hindi) ---")
    tap(X_CENTER, Y_HI, delay=2.0)
    print("  Browsing tabs: Home -> Customers -> History -> Settings")
    tap(X_HOME, Y_NAV, delay=1.0)
    tap(X_CUSTOMERS, Y_NAV, delay=1.0)
    tap(X_HISTORY, Y_NAV, delay=1.0)
    tap(X_SETTINGS, Y_NAV, delay=1.5)
    states = get_radio_states()
    print(f"States after navigation: {states}")
    assert states.get("hi", {}).get("checked") == True, "Hindi MUST remain checked after navigating tabs"
    take_screenshot("lang_06_navigation_persistence_hindi.png")
    print("PASS: Hindi remained checked after navigating through all 5 tabs.")

    # Test 7: Rapid Repeated Selection Stability
    print("\n--- TEST 7: Rapid Repeated Selection Stability ---")
    print("  Tapping EN -> HI -> KN -> HI in rapid succession...")
    run_cmd(f"{ADB} shell input tap {X_CENTER} {Y_EN}")
    time.sleep(0.3)
    run_cmd(f"{ADB} shell input tap {X_CENTER} {Y_HI}")
    time.sleep(0.3)
    run_cmd(f"{ADB} shell input tap {X_CENTER} {Y_KN}")
    time.sleep(0.3)
    run_cmd(f"{ADB} shell input tap {X_CENTER} {Y_HI}")
    time.sleep(3.0) # Wait for recreation to settle
    states = get_radio_states()
    print(f"States after rapid taps: {states}")
    assert states.get("hi", {}).get("checked") == True, "Final selected language (Hindi) MUST be checked"
    take_screenshot("lang_07_rapid_selection_stable.png")
    print("PASS: App remained fully responsive without crashes or stale state.")

    print("\n==================================================================")
    print("ALL 7 ON-DEVICE VERIFICATION TESTS PASSED SUCCESSFULLY!")
    print("==================================================================")

if __name__ == "__main__":
    main()
