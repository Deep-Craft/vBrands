# vBrands 🛰️

**vBrands** is a high-performance Velocity proxy utility designed to intercept and customize the server brand identification sent to the Minecraft client.

## 🛠 Technical Overview

Unlike standard branding tools, **vBrands** operates at the network layer to ensure precision and minimal overhead:

* **Packet Interception:** Utilizes **Netty channel injection** to intercept outbound plugin messages.
* **F3 Brand Modification:** Dynamically renames the server brand string displayed in the client's F3 debug menu.
* **Velocity Native:** Built specifically for the **Velocity Proxy** API for maximum compatibility and performance.

---

## 📂 Project Structure

This repository contains the source code and build configurations necessary to compile the plugin:

* **`src/`**: Contains the Java source code.
* **`gradle/`**: Gradle wrapper and build environment.
* **`build.gradle`**: Project dependencies and build scripts.

---

## ⚙️ Compilation

To build the plugin from source, ensure you have **Java 21/17** installed.

1. Clone the repository:
```bash
git clone https://github.com/Deep-Craft/vBrands.git

```


2. Build using the Gradle wrapper:
```bash
./gradlew build

```


3. The compiled JAR will be located in `build/libs/`.

### 🔗 Status

* **Version:** v1.0.0
* **Platform:** Velocity Proxy
* **License:** AGPL-3.0
