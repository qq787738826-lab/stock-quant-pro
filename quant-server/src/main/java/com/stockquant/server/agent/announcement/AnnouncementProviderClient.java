package com.stockquant.server.agent.announcement;

import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderRequest;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderResponse;

public interface AnnouncementProviderClient {

    ProviderResponse fetch(ProviderRequest request);
}
