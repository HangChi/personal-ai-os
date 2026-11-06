package com.personalos.workflow.dto;
public record WorkflowChange(String name,String kind,String timezone,String localTime,Integer weekday,Boolean enabled,Integer maxRunsPerDay,Integer maxItems,Integer maxOutputChars) {}
