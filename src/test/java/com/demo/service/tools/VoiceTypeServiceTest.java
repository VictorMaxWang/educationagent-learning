package com.demo.service.tools;

import com.demo.cache.ThreeTierCache;
import com.demo.entity.UserSettingsEntity;
import com.demo.mapper.UserSettingsMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoiceTypeServiceTest {
    @Test
    void newSettingsInheritConfiguredDefaultButSavedExplicitSelectionIsPreserved() {
        VoiceTypeService service = new VoiceTypeService();
        @SuppressWarnings("unchecked")
        ThreeTierCache<UserSettingsEntity> cache = mock(ThreeTierCache.class);
        ReflectionTestUtils.setField(service, "userSettingsCache", cache);
        ReflectionTestUtils.setField(service, "userSettingsMapper", mock(UserSettingsMapper.class));
        when(cache.get(eq("new-user"), any())).thenReturn(new UserSettingsEntity("new-user"));
        assertEquals("403005", service.getVoiceType("new-user"));
        assertEquals("403005", service.getVoiceType("missing-user"));

        ReflectionTestUtils.setField(service, "defaultVoiceType", "101002");
        assertEquals("101002", service.getVoiceType("new-user"));
        UserSettingsEntity custom = new UserSettingsEntity("custom-user");
        custom.setVoiceType("101007");
        when(cache.get(eq("custom-user"), any())).thenReturn(custom);
        assertEquals("101007", service.getVoiceType("custom-user"));
    }
}
