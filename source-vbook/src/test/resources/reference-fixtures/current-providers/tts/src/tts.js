'use strict';
function execute(text, voiceId) {
    return Response.success({
        text: text,
        voiceId: voiceId,
        audio: 'data:audio/mock;base64,AA=='
    });
}
