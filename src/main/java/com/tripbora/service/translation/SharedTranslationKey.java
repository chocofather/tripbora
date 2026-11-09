package com.tripbora.service.translation;

/** 정규화 profile을 완전 일치 profile과 분리한다. 콘텐츠의 raw hash에는 관여하지 않는다. */
record SharedTranslationKey(byte[] hash, String profile, boolean normalized) {
    private static final String SUFFIX = "-ws-v1";

    static SharedTranslationKey forSource(TranslationSourceSnapshot source, byte[] rawHash,
                                         TranslationProviderMetadata metadata) {
        String profile = metadata.profileFor(source.mimeType());
        var text = SharedTranslationTextNormalizer.normalize(source.text(),source.mimeType());
        // 기존 64자 profile을 잘라 서로 다른 설정을 같은 키로 합치지 않는다.
        if (text.isEmpty() || profile.length()+SUFFIX.length()>64)
            return new SharedTranslationKey(rawHash,profile,false);
        return new SharedTranslationKey(ContentTranslationService.sourceHash(text.get()),profile+SUFFIX,true);
    }
}
