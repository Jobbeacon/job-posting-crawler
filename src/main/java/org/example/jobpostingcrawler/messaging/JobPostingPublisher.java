package org.example.jobpostingcrawler.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.example.jobpostingcrawler.model.JobPosting;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobPostingPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final CrawlerProperties crawlerProperties;

    public void push(JobPosting jobPosting) {
        CrawlerProperties.RabbitProperties rabbit = crawlerProperties.rabbitmq();
        rabbitTemplate.convertAndSend(rabbit.exchange(), rabbit.routingKey(), jobPosting);
        log.info("Pushed job posting to RabbitMQ: {}", jobPosting.getUrl());
    }
}
