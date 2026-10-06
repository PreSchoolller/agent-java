package io.github.preschoolller.agentjava.backend.provider.common;

import com.openai.models.responses.ResponseAudioDeltaEvent;
import com.openai.models.responses.ResponseAudioDoneEvent;
import com.openai.models.responses.ResponseAudioTranscriptDeltaEvent;
import com.openai.models.responses.ResponseAudioTranscriptDoneEvent;
import com.openai.models.responses.ResponseCodeInterpreterCallCodeDeltaEvent;
import com.openai.models.responses.ResponseCodeInterpreterCallCodeDoneEvent;
import com.openai.models.responses.ResponseCodeInterpreterCallCompletedEvent;
import com.openai.models.responses.ResponseCodeInterpreterCallInProgressEvent;
import com.openai.models.responses.ResponseCodeInterpreterCallInterpretingEvent;
import com.openai.models.responses.ResponseCompletedEvent;
import com.openai.models.responses.ResponseContentPartAddedEvent;
import com.openai.models.responses.ResponseContentPartDoneEvent;
import com.openai.models.responses.ResponseCreatedEvent;
import com.openai.models.responses.ResponseCustomToolCallInputDeltaEvent;
import com.openai.models.responses.ResponseCustomToolCallInputDoneEvent;
import com.openai.models.responses.ResponseErrorEvent;
import com.openai.models.responses.ResponseFailedEvent;
import com.openai.models.responses.ResponseFileSearchCallCompletedEvent;
import com.openai.models.responses.ResponseFileSearchCallInProgressEvent;
import com.openai.models.responses.ResponseFileSearchCallSearchingEvent;
import com.openai.models.responses.ResponseFunctionCallArgumentsDeltaEvent;
import com.openai.models.responses.ResponseFunctionCallArgumentsDoneEvent;
import com.openai.models.responses.ResponseImageGenCallCompletedEvent;
import com.openai.models.responses.ResponseImageGenCallGeneratingEvent;
import com.openai.models.responses.ResponseImageGenCallInProgressEvent;
import com.openai.models.responses.ResponseImageGenCallPartialImageEvent;
import com.openai.models.responses.ResponseInProgressEvent;
import com.openai.models.responses.ResponseIncompleteEvent;
import com.openai.models.responses.ResponseMcpCallArgumentsDeltaEvent;
import com.openai.models.responses.ResponseMcpCallArgumentsDoneEvent;
import com.openai.models.responses.ResponseMcpCallCompletedEvent;
import com.openai.models.responses.ResponseMcpCallFailedEvent;
import com.openai.models.responses.ResponseMcpCallInProgressEvent;
import com.openai.models.responses.ResponseMcpListToolsCompletedEvent;
import com.openai.models.responses.ResponseMcpListToolsFailedEvent;
import com.openai.models.responses.ResponseMcpListToolsInProgressEvent;
import com.openai.models.responses.ResponseOutputItemAddedEvent;
import com.openai.models.responses.ResponseOutputItemDoneEvent;
import com.openai.models.responses.ResponseOutputTextAnnotationAddedEvent;
import com.openai.models.responses.ResponseQueuedEvent;
import com.openai.models.responses.ResponseReasoningSummaryPartAddedEvent;
import com.openai.models.responses.ResponseReasoningSummaryPartDoneEvent;
import com.openai.models.responses.ResponseReasoningSummaryTextDeltaEvent;
import com.openai.models.responses.ResponseReasoningSummaryTextDoneEvent;
import com.openai.models.responses.ResponseReasoningTextDeltaEvent;
import com.openai.models.responses.ResponseReasoningTextDoneEvent;
import com.openai.models.responses.ResponseRefusalDeltaEvent;
import com.openai.models.responses.ResponseRefusalDoneEvent;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import com.openai.models.responses.ResponseTextDoneEvent;
import com.openai.models.responses.ResponseWebSearchCallCompletedEvent;
import com.openai.models.responses.ResponseWebSearchCallInProgressEvent;
import com.openai.models.responses.ResponseWebSearchCallSearchingEvent;
import org.jetbrains.annotations.NotNull;

public class ResponseStreamEventVisitorAdaptor implements ResponseStreamEvent.Visitor{

    @Override
    public Object visitAudioDelta(@NotNull ResponseAudioDeltaEvent responseAudioDeltaEvent) {
        return null;
    }

    @Override
    public Object visitAudioDone(@NotNull ResponseAudioDoneEvent responseAudioDoneEvent) {
        return null;
    }

    @Override
    public Object visitAudioTranscriptDelta(@NotNull ResponseAudioTranscriptDeltaEvent responseAudioTranscriptDeltaEvent) {
        return null;
    }

    @Override
    public Object visitAudioTranscriptDone(@NotNull ResponseAudioTranscriptDoneEvent responseAudioTranscriptDoneEvent) {
        return null;
    }

    @Override
    public Object visitCodeInterpreterCallCodeDelta(@NotNull ResponseCodeInterpreterCallCodeDeltaEvent responseCodeInterpreterCallCodeDeltaEvent) {
        return null;
    }

    @Override
    public Object visitCodeInterpreterCallCodeDone(@NotNull ResponseCodeInterpreterCallCodeDoneEvent responseCodeInterpreterCallCodeDoneEvent) {
        return null;
    }

    @Override
    public Object visitCodeInterpreterCallCompleted(@NotNull ResponseCodeInterpreterCallCompletedEvent responseCodeInterpreterCallCompletedEvent) {
        return null;
    }

    @Override
    public Object visitCodeInterpreterCallInProgress(@NotNull ResponseCodeInterpreterCallInProgressEvent responseCodeInterpreterCallInProgressEvent) {
        return null;
    }

    @Override
    public Object visitCodeInterpreterCallInterpreting(@NotNull ResponseCodeInterpreterCallInterpretingEvent responseCodeInterpreterCallInterpretingEvent) {
        return null;
    }

    @Override
    public Object visitCompleted(@NotNull ResponseCompletedEvent responseCompletedEvent) {
        return null;
    }

    @Override
    public Object visitContentPartAdded(@NotNull ResponseContentPartAddedEvent responseContentPartAddedEvent) {
        return null;
    }

    @Override
    public Object visitContentPartDone(@NotNull ResponseContentPartDoneEvent responseContentPartDoneEvent) {
        return null;
    }

    @Override
    public Object visitCreated(@NotNull ResponseCreatedEvent responseCreatedEvent) {
        return null;
    }

    @Override
    public Object visitError(@NotNull ResponseErrorEvent responseErrorEvent) {
        return null;
    }

    @Override
    public Object visitFileSearchCallCompleted(@NotNull ResponseFileSearchCallCompletedEvent responseFileSearchCallCompletedEvent) {
        return null;
    }

    @Override
    public Object visitFileSearchCallInProgress(@NotNull ResponseFileSearchCallInProgressEvent responseFileSearchCallInProgressEvent) {
        return null;
    }

    @Override
    public Object visitFileSearchCallSearching(@NotNull ResponseFileSearchCallSearchingEvent responseFileSearchCallSearchingEvent) {
        return null;
    }

    @Override
    public Object visitFunctionCallArgumentsDelta(@NotNull ResponseFunctionCallArgumentsDeltaEvent responseFunctionCallArgumentsDeltaEvent) {
        return null;
    }

    @Override
    public Object visitFunctionCallArgumentsDone(@NotNull ResponseFunctionCallArgumentsDoneEvent responseFunctionCallArgumentsDoneEvent) {
        return null;
    }

    @Override
    public Object visitInProgress(@NotNull ResponseInProgressEvent responseInProgressEvent) {
        return null;
    }

    @Override
    public Object visitFailed(@NotNull ResponseFailedEvent responseFailedEvent) {
        return null;
    }

    @Override
    public Object visitIncomplete(@NotNull ResponseIncompleteEvent responseIncompleteEvent) {
        return null;
    }

    @Override
    public Object visitOutputItemAdded(@NotNull ResponseOutputItemAddedEvent responseOutputItemAddedEvent) {
        return null;
    }

    @Override
    public Object visitOutputItemDone(@NotNull ResponseOutputItemDoneEvent responseOutputItemDoneEvent) {
        return null;
    }

    @Override
    public Object visitReasoningSummaryPartAdded(@NotNull ResponseReasoningSummaryPartAddedEvent responseReasoningSummaryPartAddedEvent) {
        return null;
    }

    @Override
    public Object visitReasoningSummaryPartDone(@NotNull ResponseReasoningSummaryPartDoneEvent responseReasoningSummaryPartDoneEvent) {
        return null;
    }

    @Override
    public Object visitReasoningSummaryTextDelta(@NotNull ResponseReasoningSummaryTextDeltaEvent responseReasoningSummaryTextDeltaEvent) {
        return null;
    }

    @Override
    public Object visitReasoningSummaryTextDone(@NotNull ResponseReasoningSummaryTextDoneEvent responseReasoningSummaryTextDoneEvent) {
        return null;
    }

    @Override
    public Object visitReasoningTextDelta(@NotNull ResponseReasoningTextDeltaEvent responseReasoningTextDeltaEvent) {
        return null;
    }

    @Override
    public Object visitReasoningTextDone(@NotNull ResponseReasoningTextDoneEvent responseReasoningTextDoneEvent) {
        return null;
    }

    @Override
    public Object visitRefusalDelta(@NotNull ResponseRefusalDeltaEvent responseRefusalDeltaEvent) {
        return null;
    }

    @Override
    public Object visitRefusalDone(@NotNull ResponseRefusalDoneEvent responseRefusalDoneEvent) {
        return null;
    }

    @Override
    public Object visitOutputTextDelta(@NotNull ResponseTextDeltaEvent responseTextDeltaEvent) {
        return null;
    }

    @Override
    public Object visitOutputTextDone(@NotNull ResponseTextDoneEvent responseTextDoneEvent) {
        return null;
    }

    @Override
    public Object visitWebSearchCallCompleted(@NotNull ResponseWebSearchCallCompletedEvent responseWebSearchCallCompletedEvent) {
        return null;
    }

    @Override
    public Object visitWebSearchCallInProgress(@NotNull ResponseWebSearchCallInProgressEvent responseWebSearchCallInProgressEvent) {
        return null;
    }

    @Override
    public Object visitWebSearchCallSearching(@NotNull ResponseWebSearchCallSearchingEvent responseWebSearchCallSearchingEvent) {
        return null;
    }

    @Override
    public Object visitImageGenerationCallCompleted(@NotNull ResponseImageGenCallCompletedEvent responseImageGenCallCompletedEvent) {
        return null;
    }

    @Override
    public Object visitImageGenerationCallGenerating(@NotNull ResponseImageGenCallGeneratingEvent responseImageGenCallGeneratingEvent) {
        return null;
    }

    @Override
    public Object visitImageGenerationCallInProgress(@NotNull ResponseImageGenCallInProgressEvent responseImageGenCallInProgressEvent) {
        return null;
    }

    @Override
    public Object visitImageGenerationCallPartialImage(@NotNull ResponseImageGenCallPartialImageEvent responseImageGenCallPartialImageEvent) {
        return null;
    }

    @Override
    public Object visitMcpCallArgumentsDelta(@NotNull ResponseMcpCallArgumentsDeltaEvent responseMcpCallArgumentsDeltaEvent) {
        return null;
    }

    @Override
    public Object visitMcpCallArgumentsDone(@NotNull ResponseMcpCallArgumentsDoneEvent responseMcpCallArgumentsDoneEvent) {
        return null;
    }

    @Override
    public Object visitMcpCallCompleted(@NotNull ResponseMcpCallCompletedEvent responseMcpCallCompletedEvent) {
        return null;
    }

    @Override
    public Object visitMcpCallFailed(@NotNull ResponseMcpCallFailedEvent responseMcpCallFailedEvent) {
        return null;
    }

    @Override
    public Object visitMcpCallInProgress(@NotNull ResponseMcpCallInProgressEvent responseMcpCallInProgressEvent) {
        return null;
    }

    @Override
    public Object visitMcpListToolsCompleted(@NotNull ResponseMcpListToolsCompletedEvent responseMcpListToolsCompletedEvent) {
        return null;
    }

    @Override
    public Object visitMcpListToolsFailed(@NotNull ResponseMcpListToolsFailedEvent responseMcpListToolsFailedEvent) {
        return null;
    }

    @Override
    public Object visitMcpListToolsInProgress(@NotNull ResponseMcpListToolsInProgressEvent responseMcpListToolsInProgressEvent) {
        return null;
    }

    @Override
    public Object visitOutputTextAnnotationAdded(@NotNull ResponseOutputTextAnnotationAddedEvent responseOutputTextAnnotationAddedEvent) {
        return null;
    }

    @Override
    public Object visitQueued(@NotNull ResponseQueuedEvent responseQueuedEvent) {
        return null;
    }

    @Override
    public Object visitCustomToolCallInputDelta(@NotNull ResponseCustomToolCallInputDeltaEvent responseCustomToolCallInputDeltaEvent) {
        return null;
    }

    @Override
    public Object visitCustomToolCallInputDone(@NotNull ResponseCustomToolCallInputDoneEvent responseCustomToolCallInputDoneEvent) {
        return null;
    }
}
