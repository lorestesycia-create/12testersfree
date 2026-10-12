# 12TestersFree

Android Kotlin; Java 17, AGP 8.6.1, Gradle 8.7, compile/target SDK 35.

## Estado real

- Anuncios: modo prueba predeterminado incluso en release. Para habilitar anuncios reales en una compilación final: `-PproductionAds=true` (solo tras validar SSV y consentimiento).
- Banner y recompensados integrados con UMP. No se conceden monedas desde el dispositivo.
- Receptor AdMob SSV preparado en `supabase/functions/admob-ssv/index.ts`, **pendiente de desplegar y probar**. Debe desplegarse como webhook público sin JWT, con firma Google verificada en código, y registrar la URL en AdMob.
- Verificación de pruebas de terceros: se registran como pendientes; falta una fuente independiente de validación antes de acreditar 20 monedas.
- Google OAuth: requiere configuración del proveedor y URL de retorno en Supabase y Google Cloud.
- VersionCode 1 / VersionName 1.0.0; comprobar Play Console antes de AAB.
- Compilación aún no ejecutada; no afirmar que está verificada.
