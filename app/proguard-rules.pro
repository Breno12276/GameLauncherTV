# A reflexão do app é em classes do sistema (serviço de janelas), que o R8 não renomeia;
# a HiddenApiBypass traz as regras dela.
# Shizuku: o "newProcess" é chamado por reflexão (é privado na API 13).
-keep class rikka.shizuku.Shizuku { *; }
