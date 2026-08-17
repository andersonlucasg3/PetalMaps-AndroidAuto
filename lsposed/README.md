# Petal Maps Hidden API Unlock (módulo LSPosed)

Módulo LSPosed que isenta hidden APIs no processo do Petal Maps
(`com.huawei.maps.app`). A extension injetada no APK usa reflexão em APIs
`@hide` — `android.window.ScreenCapture`, `SurfaceControl.screenshot` — e a
isenção de hidden APIs é por processo, então precisa ser aplicada dentro do
processo do Petal Maps antes dessas chamadas.

## Técnica de isenção

- Em API 28–33, módulos chamavam `dalvik.system.VMRuntime.setHiddenApiExemptions`
  via meta-reflexão; isso morreu no Android 11 (`PreventMetaReflectionBlocklistAccess`).
- Em Android 14/15 (API 34/35), o método **continua existindo e sem gate de caller**
  no ART ([runtime.h](https://android.googlesource.com/platform/art/+/refs/heads/android15-release/runtime/runtime.h) —
  `Runtime::SetHiddenApiExemptions` apenas substitui a lista), e a lista é consultada
  primeiro para callers de domínio application
  ([hidden_api.cc](https://android.googlesource.com/platform/art/+/refs/heads/android15-release/runtime/hidden_api.cc) —
  `DoesPrefixMatchAny(runtime->GetHiddenApiExemptions())`). O que é bloqueado é o
  *lookup reflexivo* do próprio método (domínio do caller vem do dex do módulo).
- Este módulo faz o bootstrap da chamada com a biblioteca oficial do LSPosed
  [LSPosed/AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)
  (`org.lsposed.hiddenapibypass:hiddenapibypass`, mantida 2021–2025, suporte declarado
  Android 1.0–17): variante `HiddenApiBypass` (Unsafe) com fallback `LSPass`.
  O prefixo `"L"` isenta todas as APIs hidden do processo; os prefixos específicos
  (`ScreenCapture`, `SurfaceControl`, etc.) são redundantes com `"L"`, mas ficam
  listados por clareza.
- O módulo verifica o resultado e loga em logcat (tag `PetalMapsHiddenApi`):
  `Verification: SurfaceControl.screenshot via reflection ACCESSIBLE`.

## Requisitos

- LSPosed 1.9.x (Zygisk) — versão estável mais recente: v1.9.2.
- O módulo usa o formato legacy (manifest meta-data + `assets/xposed_init` +
  `IXposedHookLoadPackage`), suportado por LSPosed 1.8.x–1.10.x.

## Build

O subprojeto é independente do build raiz (que aplica o plugin Morphe) e usa o
wrapper da raiz com o settings próprio:

```bash
cd lsposed && ../gradlew -p . assembleRelease
```

(No Windows: `..\gradlew.bat -p . assembleRelease`.)

- Gradle 9.6.1 (wrapper da raiz), AGP 9.1.0, JDK 21 (JBR do Android Studio,
  fixado em `gradle.properties`), SDK 36 (`local.properties`).
- O APK assinado sai em `lsposed/app/build/outputs/apk/release/app-release.apk`
  (assinado com o debug keystore local — módulo de uso pessoal).

## Instalação e ativação

1. Instale o APK: `adb install lsposed/app/build/outputs/apk/release/app-release.apk`.
2. Abra o LSPosed Manager → **Módulos** → ative **Petal Maps Hidden API Unlock**.
3. No escopo do módulo, marque **com.huawei.maps.app** (o manifest já sugere o
   escopo via meta-data `xposedscope`; confirme na UI).
4. Force parada do Petal Maps (ou reinicie) — o módulo entra em vigor ao carregar
   o processo, antes de `Application.onCreate`.

## Verificação

```bash
adb logcat -s PetalMapsHiddenApi:I
```

Procure por `setHiddenApiExemptions: OK` e
`Verification: SurfaceControl.screenshot via reflection ACCESSIBLE`. A isenção
vale por processo: se o Petal Maps tiver processos secundários, cada um aplica
a própria isenção ao carregar.
