.PHONY: build build-release test lint run connected-test clean full-test \
       gui cli gui-release cli-release release appimage

build:
	./gradlew assembleDebug

build-release:
	./gradlew assembleRelease

test:
	./gradlew testDebugUnitTest

lint:
	./gradlew lint

run:
	./gradlew installDebug

connected-test:
	./gradlew connectedDebugAndroidTest

clean:
	./gradlew clean

full-test:
	python3 build_and_test.py

gui:
	cargo run --manifest-path vault-native/Cargo.toml -p librecrate-gui

cli:
	cargo run --manifest-path vault-native/Cargo.toml -p librecrate

gui-release:
	cargo build --release --manifest-path vault-native/Cargo.toml -p librecrate-gui

cli-release:
	cargo build --release --manifest-path vault-native/Cargo.toml -p librecrate

release:
	packaging/release.sh

appimage:
	packaging/appimage/build-appimage.sh
