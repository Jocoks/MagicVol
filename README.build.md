# 构建说明（补充）

`build.sh` 依赖两样不在仓库里的东西（体积原因没提交）：

- `tools/android.jar` —— 任意 Android SDK Platform 的 `android.jar`（本机用的是 android-36）
- `tools/libxposed-api.jar` —— [libxposed/api](https://github.com/libxposed/api) 的 jar（API 102）

放到 `tools/` 下，或者用环境变量指到别处：

```sh
AAPT2=/path/to/aapt2 \
ANDROID_JAR=/path/to/android.jar \
D8_JAR=/path/to/d8.jar \
./build.sh
```

`--sign` 用的 `tools/debug.keystore` 也不会提交（自己 `keytool` 生成一个即可）：
debug 签名包**只能自测**，正式发布请用你自己的 keystore 签名。
