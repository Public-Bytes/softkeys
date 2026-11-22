# Contributing to SoftKeys

Thanks for trying SoftKeys. Bug reports, ideas, and pull requests are welcome.

## Reporting a problem

[Open an issue](https://github.com/gitcommit90/softkeys/issues/new/choose) and choose **Bug report**. These details help most:

- Your phone model and Android version, and the SoftKeys version from the SoftKeys app.
- The app you were typing in.
- What you did, what you expected, and what happened instead. A screenshot or screen recording helps with layout problems.
- For dictation problems: the engine (On-device or Phone speech service) and the model.

Please leave out anything private. Don't paste messages, passwords, or other personal text into an issue, and use sample text in screenshots.

## Suggesting a feature

Choose **Feature request** when you open an issue. SoftKeys aims to feel like the iOS keyboard while keeping typing on the phone, so ideas that send typed text off the phone or add tracking won't be accepted.

## Changing the code

SoftKeys is a native Java Android app with no third-party Java dependencies apart from the sherpa-onnx speech library. [docs/development.md](docs/development.md) explains the source layout, how to build, the tests and benchmarks, and the probe app for testing editors.

1. Fork the repository and create a branch from `main`.
2. Build with `./gradlew :app:assembleRelease --console=plain` using Java 17 and Android SDK platform 36.
3. Run the checks described in [docs/development.md](docs/development.md) that apply to your change.
4. Try the change on a phone or emulator, and describe what you tested in the pull request.
5. Update README.md or docs/development.md when behavior changes.

Keep pull requests focused on one change. By contributing, you agree that your contribution is released under the [MIT License](LICENSE).

## Security

If you find a problem that could expose what someone types or says, please don't post the details publicly. Open an issue that asks for a private contact, or use GitHub's private vulnerability reporting if it is enabled for this repository.
