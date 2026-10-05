package com.youfree.island

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams

/** Chamada ao Claude para perguntas que os comandos locais não resolvem. Bloqueante: use fora da thread principal. */
class ClaudeClient(apiKey: String, private val model: String) {

    data class Turn(val fromUser: Boolean, val text: String)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    fun reply(system: String, history: List<Turn>, userText: String): String {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(1024L)
            .system(system)

        if (model in MODELS_WITH_FALLBACKS) {
            // Se um filtro de segurança recusar a pergunta, o servidor tenta outro modelo automaticamente.
            builder.addBeta("server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        if (!model.startsWith("claude-haiku")) {
            // Respostas curtas e rápidas para voz.
            builder.outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
        }

        for (turn in history) {
            if (turn.fromUser) builder.addUserMessage(turn.text) else builder.addAssistantMessage(turn.text)
        }
        builder.addUserMessage(userText)

        val message = try {
            client.beta().messages().create(builder.build())
        } catch (e: UnauthorizedException) {
            return "Sua chave da API do Claude é inválida. Confira nas configurações do app."
        } catch (e: PermissionDeniedException) {
            return "Sua chave da API não tem permissão para usar o modelo $model."
        } catch (e: RateLimitException) {
            return "Muitas perguntas seguidas. Espere um pouquinho e tente de novo."
        } catch (e: AnthropicServiceException) {
            return "O Claude respondeu com erro ${e.statusCode()}. Tente de novo."
        } catch (e: AnthropicIoException) {
            return "Sem conexão com a internet agora."
        }

        if (message.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
            return "Desculpe, não posso ajudar com isso."
        }
        val text = message.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("")
            .trim()
        return text.ifEmpty { "Não consegui pensar numa resposta." }
    }

    fun close() = client.close()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        private val MODELS_WITH_FALLBACKS = setOf(
            "claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-sonnet-5-5",
        )
    }
}
