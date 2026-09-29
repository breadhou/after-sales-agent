package com.mall.agent.knowledge;

import java.util.List;

/** Untrusted no-tool model draft; trusted code validates every field before display. */
public record ExplanationDraft(String narrative, List<String> citedSourceIds) {
}
