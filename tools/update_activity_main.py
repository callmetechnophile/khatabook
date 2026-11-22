path = r'D:\bwb2\app\src\main\res\layout\activity_main.xml'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

target = """            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:orientation="vertical">"""

replacement = """            <ImageView
                android:id="@+id/iv_brand_logo"
                android:layout_width="40dp"
                android:layout_height="40dp"
                android:layout_marginEnd="12dp"
                android:src="@mipmap/ic_launcher"
                android:contentDescription="@string/app_name" />

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:orientation="vertical">"""

if target in content:
    content = content.replace(target, replacement, 1)
    with open(path, 'w', encoding='utf-8', newline='\r\n') as f:
        f.write(content)
    print("Updated activity_main.xml successfully.")
else:
    print("Target not found. Please check content.")
