import * as React from "react";

import { useMutation } from "@tanstack/react-query";
import type { TFunction } from "i18next";
import { ChevronDown, Earth, LoaderCircle } from "lucide-react";
import { useTranslation } from "react-i18next";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { usePickerPopover } from "~/hooks/use-picker-popover";
import { extractErrorMessage } from "~/lib/error";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import type { SearchServiceOption } from "~/types";
import { AIIcon } from "~/components/ui/ai-icon";
import { Button } from "~/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from "~/components/ui/popover";
import { ScrollArea } from "~/components/ui/scroll-area";
import { Switch } from "~/components/ui/switch";

import { PickerErrorAlert } from "./picker-error-alert";

const SEARCH_SERVICE_LABELS: Record<string, string> = {
  bing_local: "Bing",
  aiagents: "AI Agents",
  zhipu: "智谱",
  tavily: "Tavily",
  exa: "Exa",
  searxng: "SearXNG",
  linkup: "LinkUp",
  brave: "Brave",
  metaso: "秘塔",
  ollama: "Ollama",
  perplexity: "Perplexity",
  firecrawl: "Firecrawl",
  jina: "Jina",
  bocha: "博查",
};

export interface SearchPickerButtonProps {
  disabled?: boolean;
  className?: string;
}

function getServiceType(service: SearchServiceOption): string | null {
  if (typeof service.type !== "string") {
    return null;
  }

  const value = service.type.trim().toLowerCase();
  return value.length > 0 ? value : null;
}

function getServiceLabel(service: SearchServiceOption, t: TFunction): string {
  const type = getServiceType(service);
  if (!type) {
    return t("search.default_service_label");
  }

  return SEARCH_SERVICE_LABELS[type] ?? type;
}

export function SearchPickerButton({ disabled = false, className }: SearchPickerButtonProps) {
  const { t } = useTranslation("input");
  const { settings, currentAssistant } = useCurrentAssistant();

  const canUse = Boolean(settings && currentAssistant && !disabled);
  const { error, setError, popoverProps } = usePickerPopover(canUse);

  const searchEnabled = currentAssistant?.enableWebSearch ?? false;
  const currentService = settings?.searchServices?.[settings.searchServiceSelected] ?? null;
  const checked = searchEnabled;

  React.useEffect(() => {
    if (!canUse) {
      popoverProps.onOpenChange(false);
    }
  }, [canUse]);

  const toggleSearchEnabledMutation = useMutation({
    mutationFn: ({ enabled }: { enabled: boolean }) =>
      api.post<{ status: string }>("settings/search/enabled", {
        assistantId: currentAssistant?.id,
        enabled,
      }),
    onError: (toggleError) => {
      setError(extractErrorMessage(toggleError, t("search.update_search_failed")));
    },
    onSuccess: () => setError(null),
  });

  const selectServiceMutation = useMutation({
    mutationFn: ({ index }: { index: number }) =>
      api.post<{ status: string }>("settings/search/service", { index }),
    onError: (serviceError) => {
      setError(extractErrorMessage(serviceError, t("search.switch_service_failed")));
    },
    onSuccess: () => setError(null),
  });

  const loading = toggleSearchEnabledMutation.isPending || selectServiceMutation.isPending;

  return (
    <Popover {...popoverProps}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={!canUse || loading}
          className={cn(
            "h-8 rounded-full px-2 text-muted-foreground hover:text-foreground",
            checked && "text-primary hover:bg-primary/10",
            className,
          )}
        >
          {toggleSearchEnabledMutation.isPending ? (
            <LoaderCircle className="size-4 animate-spin" />
          ) : searchEnabled && currentService ? (
            <AIIcon
              name={getServiceLabel(currentService, t)}
              size={16}
              className="bg-transparent"
              imageClassName="h-full w-full"
            />
          ) : (
            <Earth className="size-4" />
          )}
          <span className="hidden sm:block">
            <ChevronDown className="size-3.5" />
          </span>
        </Button>
      </PopoverTrigger>

      <PopoverContent align="end" className="w-[min(92vw,28rem)] gap-0 p-0">
        <PopoverHeader className="border-b px-6 py-4">
          <PopoverTitle>{t("search.title")}</PopoverTitle>
          <PopoverDescription>{t("search.description")}</PopoverDescription>
        </PopoverHeader>

        <div className="space-y-4 px-4 py-4">
          <PickerErrorAlert error={error} />

          <div className="flex items-center gap-3 rounded-lg border px-3 py-3">
            <div className="flex size-8 shrink-0 items-center justify-center rounded-full bg-muted">
              <Earth className="size-4" />
            </div>
            <div className="min-w-0 flex-1">
              <div className="text-sm font-medium">{t("search.web_title")}</div>
              <div className="text-muted-foreground text-xs">
                {searchEnabled ? t("search.status_enabled") : t("search.status_disabled")}
              </div>
            </div>
            <Switch
              checked={searchEnabled}
              disabled={disabled || loading}
              onCheckedChange={(nextChecked) => {
                if (!canUse) return;
                toggleSearchEnabledMutation.mutate({ enabled: nextChecked });
              }}
            />
          </div>

          <ScrollArea className="h-[16rem] pr-3">
            {settings?.searchServices?.length ? (
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                {settings.searchServices.map((service, index) => {
                  const selected = index === settings.searchServiceSelected;
                  const switching =
                    selectServiceMutation.isPending &&
                    selectServiceMutation.variables?.index === index;

                  return (
                    <button
                      key={service.id}
                      type="button"
                      className={cn(
                        "hover:bg-muted flex w-full items-center gap-3 rounded-lg border px-3 py-2 text-left transition",
                        selected && "border-primary bg-primary/5",
                      )}
                      disabled={disabled || loading}
                      onClick={() => {
                        if (!canUse || !settings || index === settings.searchServiceSelected)
                          return;
                        selectServiceMutation.mutate({ index });
                      }}
                    >
                      <AIIcon
                        name={getServiceLabel(service, t)}
                        size={20}
                        className="bg-transparent"
                        imageClassName="h-full w-full"
                      />
                      <div className="min-w-0 flex-1">
                        <div className="truncate text-sm font-medium">
                          {getServiceLabel(service, t)}
                        </div>
                        <div className="text-muted-foreground truncate text-xs">
                          {getServiceType(service) ?? t("search.unknown")}
                        </div>
                      </div>
                      {switching ? <LoaderCircle className="size-3.5 animate-spin" /> : null}
                    </button>
                  );
                })}
              </div>
            ) : (
              <div className="rounded-md border border-dashed px-3 py-8 text-center text-sm text-muted-foreground">
                {t("search.empty")}
              </div>
            )}
          </ScrollArea>
        </div>
      </PopoverContent>
    </Popover>
  );
}
